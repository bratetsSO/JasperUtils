import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.*;
import java.util.List;

public class JasperGeneratorApp extends JFrame {

    private JTextField fileTextField;
    private JComboBox<String> tagComboBox;
    private JTextArea outputTextArea;
    private JButton loadStructureButton;
    private JButton generateButton;
    private JButton browseButton;
    private JButton copyButton;

    private JTree xmlTree;
    private DefaultTreeModel treeModel;
    private CheckboxNode rootCheckboxNode;

    private final Set<String> seenXpaths = new HashSet<>();
    private Document currentDoc;

    public JasperGeneratorApp() {
        setTitle("Генератор полей и переменных для JasperReports (Выбор полей и атрибутов)");
        setSize(1200, 750);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // Панель ввода параметров (Верхняя часть)
        JPanel inputPanel = new JPanel(new GridBagLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // Строка 1: Выбор XML файла
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        inputPanel.add(new JLabel("Путь к XML-файлу:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        fileTextField = new JTextField();
        inputPanel.add(fileTextField, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        browseButton = new JButton("Обзор...");
        inputPanel.add(browseButton, gbc);

        gbc.gridx = 3;
        loadStructureButton = new JButton("Загрузить структуру XML");
        loadStructureButton.setFont(new Font("Arial", Font.BOLD, 12));
        inputPanel.add(loadStructureButton, gbc);

        // Строка 2: Настройка стартового тега
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1;
        inputPanel.add(new JLabel("Стартовый тег:"), gbc);

        gbc.gridx = 1; gbc.gridy = 1; gbc.gridwidth = 1;
        tagComboBox = new JComboBox<>();
        tagComboBox.setEditable(true);
        tagComboBox.setPreferredSize(new Dimension(300, tagComboBox.getPreferredSize().height));
        inputPanel.add(tagComboBox, gbc);

        gbc.gridx = 2; gbc.gridwidth = 1;
        JButton applyButton = new JButton("Применить");
        applyButton.setFont(new Font("Arial", Font.BOLD, 12));
        inputPanel.add(applyButton, gbc);

        gbc.gridx = 3; gbc.gridwidth = 1;
        generateButton = new JButton("Сгенерировать код для .JRXML");
        generateButton.setFont(new Font("Arial", Font.BOLD, 12));
        generateButton.setEnabled(false);
        inputPanel.add(generateButton, gbc);

        gbc.gridx = 3; gbc.gridwidth = 1;
        copyButton = new JButton("Копировать в буфер обмена");
        copyButton.setFont(new Font("Arial", Font.BOLD, 12));
        copyButton.setEnabled(false);
        inputPanel.add(copyButton, gbc);

        // Центральная часть: Сплит-панель (Дерево слева, Результат справа)
        rootCheckboxNode = new CheckboxNode("Структура не загружена", "", "", false);
        DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode(rootCheckboxNode);
        treeModel = new DefaultTreeModel(treeRoot);
        xmlTree = new JTree(treeModel);
        xmlTree.setCellRenderer(new CheckboxNodeRenderer());
        xmlTree.setSelectionRow(0);

        JPopupMenu filterPopupMenu = new JPopupMenu();
        JMenuItem addFilterMenuItem = new JMenuItem("Добавить фильтр...");
        filterPopupMenu.add(addFilterMenuItem);

        JMenuItem clearFiltersMenuItem = new JMenuItem("Очистить все фильтры");
        clearFiltersMenuItem.setEnabled(false);
        filterPopupMenu.add(clearFiltersMenuItem);

        xmlTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = xmlTree.getRowForLocation(e.getX(), e.getY());
                if (row != -1) {
                    TreePath path = xmlTree.getPathForLocation(e.getX(), e.getY());
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                    CheckboxNode checkNode = (CheckboxNode) node.getUserObject();

                    if (SwingUtilities.isRightMouseButton(e)) {
                        xmlTree.setSelectionPath(path);
                        if (checkNode.isLeafField) {
                            addFilterMenuItem.setEnabled(true);
                            clearFiltersMenuItem.setEnabled(false);
                            String label = checkNode.isAttribute ? checkNode.nodeName.replace("@", "") : checkNode.nodeName;
                            addFilterMenuItem.setText("Добавить фильтр для «" + label + "»...");
                            filterPopupMenu.show(e.getComponent(), e.getX(), e.getY());
                        } else if (checkNode.hasFilters()) {
                            addFilterMenuItem.setEnabled(false);
                            clearFiltersMenuItem.setEnabled(true);
                            filterPopupMenu.show(e.getComponent(), e.getX(), e.getY());
                        } else {
                            addFilterMenuItem.setEnabled(false);
                            clearFiltersMenuItem.setEnabled(false);
                        }
                    } else {
                        if (!checkNode.isTriggeredNode) {
                            return;
                        }
                        boolean nextState = !checkNode.isSelected;
                        checkNode.isSelected = nextState;
                        toggleChildSelection(node, nextState);
                        treeModel.nodeChanged(node);
                        xmlTree.repaint();
                    }
                }
            }
        });

        addFilterMenuItem.addActionListener(e -> openFilterDialog());

        clearFiltersMenuItem.addActionListener(e -> {
            TreePath selectedPath = xmlTree.getSelectionPath();
            if (selectedPath != null) {
                DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
                CheckboxNode checkNode = (CheckboxNode) selectedNode.getUserObject();
                if (!checkNode.isLeafField && checkNode.hasFilters()) {
                    checkNode.filters.clear();
                    xmlTree.repaint();
                    JOptionPane.showMessageDialog(JasperGeneratorApp.this,
                            "Фильтры очищены для «" + checkNode.nodeName + "»",
                            "Успех", JOptionPane.INFORMATION_MESSAGE);
                }
            }
        });

        applyButton.addActionListener(e -> {
            String selectedTag = (String) tagComboBox.getSelectedItem();
            if (selectedTag != null && !selectedTag.trim().isEmpty() && currentDoc != null) {
                rebuildTreeWithStartTag(selectedTag.trim());
            }
        });

        JScrollPane treeScrollPane = new JScrollPane(xmlTree);
        treeScrollPane.setBorder(BorderFactory.createTitledBorder("Выберите поля и атрибуты (@) галочками"));

        outputTextArea = new JTextArea();
        outputTextArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        outputTextArea.setEditable(false);
        JScrollPane textScrollPane = new JScrollPane(outputTextArea);
        textScrollPane.setBorder(BorderFactory.createTitledBorder("Результат генерации"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScrollPane, textScrollPane);
        splitPane.setDividerLocation(480);

        // Компоновка
        setLayout(new BorderLayout());
        add(inputPanel, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);

        // События кнопок
        browseButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("XML файлы (*.xml)", "xml"));
            int returnValue = fileChooser.showOpenDialog(this);
            if (returnValue == JFileChooser.APPROVE_OPTION) {
                fileTextField.setText(fileChooser.getSelectedFile().getAbsolutePath());
            }
        });

        loadStructureButton.addActionListener(e -> parseXmlStructure());
        generateButton.addActionListener(e -> generateCodeFromSelected());

        copyButton.addActionListener(e -> {
            String textToCopy = outputTextArea.getText();
            if (!textToCopy.isEmpty()) {
                StringSelection stringSelection = new StringSelection(textToCopy);
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(stringSelection, null);
                JOptionPane.showMessageDialog(this, "Код успешно скопирован!", "Успех", JOptionPane.INFORMATION_MESSAGE);
            }
        });

    }

    private void openFilterDialog() {
        TreePath selectedPath = xmlTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        CheckboxNode checkNode = (CheckboxNode) selectedNode.getUserObject();

        if (!checkNode.isLeafField) {
            JOptionPane.showMessageDialog(this, "Выберите конечное поле для добавления фильтра.",
                    "Внимание", JOptionPane.WARNING_MESSAGE);
            return;
        }

        DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) selectedNode.getParent();
        if (parentNode == null) return;
        CheckboxNode parentCheckNode = (CheckboxNode) parentNode.getUserObject();

        String label = checkNode.nodeName;
        String displayLabel = label.replace("@", "");
        String filterValue = JOptionPane.showInputDialog(this,
                "Введите значение фильтра:\n\n"
                        + "Поле: " + displayLabel + "\n"
                        + "Родитель: " + parentCheckNode.nodeName + "\n\n"
                        + "Результат: [" + label + "='value']",
                "Добавить фильтр", JOptionPane.INFORMATION_MESSAGE);

        if (filterValue != null && !filterValue.trim().isEmpty()) {
            String condition = label + "='" + filterValue.trim() + "'";
            parentCheckNode.addFilter(condition, parentCheckNode.nodeName);
            xmlTree.repaint();
            JOptionPane.showMessageDialog(this,
                    "Фильтр добавлен!\n[" + condition + "]",
                    "Успех", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void toggleChildSelection(DefaultMutableTreeNode parent, boolean isSelected) {
        int childCount = parent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            CheckboxNode checkNode = (CheckboxNode) child.getUserObject();
            // Устанавливаем галочку только на листьях (поля и атрибуты), не на родителях
            if (checkNode.isLeafField) {
                checkNode.isSelected = isSelected;
                treeModel.nodeChanged(child);
            }
        }
    }

    private void parseXmlStructure() {
        String filePath = fileTextField.getText().trim();
        String startTag = ((String) tagComboBox.getSelectedItem());
        startTag = startTag != null ? startTag.trim() : "";

        if (filePath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Пожалуйста, выберите XML-файл.", "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File xmlFile = new File(filePath);
        if (!xmlFile.exists()) {
            JOptionPane.showMessageDialog(this, "Указанный файл не найден.", "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            dbFactory.setNamespaceAware(false);
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            currentDoc = dBuilder.parse(xmlFile);
            currentDoc.getDocumentElement().normalize();

            DefaultMutableTreeNode treeRoot = (DefaultMutableTreeNode) treeModel.getRoot();
            treeRoot.removeAllChildren();

            rootCheckboxNode = new CheckboxNode(currentDoc.getDocumentElement().getNodeName(), "", "", false);
            treeRoot.setUserObject(rootCheckboxNode);

            // Заполняем выпадающий список родительскими тегами (исключая ListOf)
            populateStartTagOptions(currentDoc.getDocumentElement());

            buildTreeNodes(currentDoc.getDocumentElement(), treeRoot, "", "", false, startTag);

            // Устанавливаем startTag на все узлы дерева
            DefaultMutableTreeNode treeRootNode = (DefaultMutableTreeNode) treeModel.getRoot();
            updateStartTagInTree(treeRootNode, startTag);

            treeModel.reload();
            for (int i = 0; i < xmlTree.getRowCount(); i++) {
                xmlTree.expandRow(i);
            }

            generateButton.setEnabled(true);
            outputTextArea.setText("");
            copyButton.setEnabled(false);
            JOptionPane.showMessageDialog(this, "Структура XML успешно загружена!", "Успех", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Ошибка разбора структуры:\n" + ex.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
            ex.printStackTrace();
        }
    }

    private void populateStartTagOptions(Element rootElement) {
        // Сохраняем текущее значение пользователя
        String savedValue = (String) tagComboBox.getEditor().getItem();

        // Очищаем текущие опции
        tagComboBox.removeAllItems();

        // Собираем только родительские теги (у которых есть дочерние элементы), исключая ListOf
        Set<String> parentTags = new LinkedHashSet<>();
        collectParentTags(rootElement, parentTags);

        // Добавляем в комбо-бокс (пустой элемент для «использовать корень»)
        tagComboBox.addItem("");
        for (String tag : parentTags) {
            tagComboBox.addItem(tag);
        }

        // Восстанавливаем значение пользователя, если оно есть в списке, иначе оставляем как есть
        if (savedValue != null && !savedValue.trim().isEmpty()) {
            boolean found = false;
            for (int i = 0; i < tagComboBox.getItemCount(); i++) {
                if (tagComboBox.getItemAt(i).equals(savedValue)) {
                    tagComboBox.setSelectedIndex(i);
                    found = true;
                    break;
                }
            }
            // Если не нашли в списке — добавляем как пользовательское значение
            if (!found) {
                tagComboBox.getEditor().setItem(savedValue);
            }
        }
    }

    private void updateNodeTriggeredState(DefaultMutableTreeNode node, String startTag, boolean isTriggered) {
        CheckboxNode checkNode = (CheckboxNode) node.getUserObject();

        if (!startTag.isEmpty() && !isTriggered) {
            String nodeName = checkNode.nodeName.replace("@", "");
            if (nodeName.equals(startTag)) {
                isTriggered = true;
            }
        }

        checkNode.isTriggeredNode = isTriggered;

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            updateNodeTriggeredState((DefaultMutableTreeNode) node.getChildAt(i), startTag, isTriggered);
        }
    }

    private void updateStartTagInTree(DefaultMutableTreeNode node, String startTag) {
        CheckboxNode checkNode = (CheckboxNode) node.getUserObject();
        checkNode.setStartTag(startTag);
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            updateStartTagInTree((DefaultMutableTreeNode) node.getChildAt(i), startTag);
        }
    }

    private void rebuildTreeWithStartTag(String startTag) {
        DefaultMutableTreeNode treeRoot = (DefaultMutableTreeNode) treeModel.getRoot();
        treeRoot.removeAllChildren();
        CheckboxNode rootNode = new CheckboxNode(currentDoc.getDocumentElement().getNodeName(), "", "", false);
        treeRoot.setUserObject(rootNode);

        populateStartTagOptions(currentDoc.getDocumentElement());
        buildTreeNodes(currentDoc.getDocumentElement(), treeRoot, "", "", false, startTag);
        updateStartTagInTree(treeRoot, startTag);
        treeModel.reload();
        for (int i = 0; i < xmlTree.getRowCount(); i++) {
            xmlTree.expandRow(i);
        }
        xmlTree.repaint();
        JOptionPane.showMessageDialog(this, "Дерево перестроено для тега: " + startTag, "Успех", JOptionPane.INFORMATION_MESSAGE);
    }

    private void collectParentTags(Element element, Set<String> seenTags) {
        boolean hasChildren = false;
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                hasChildren = true;
                break;
            }
        }

        String tagName = element.getNodeName();
        String cleanTagName = tagName.contains(":") ? tagName.substring(tagName.lastIndexOf(":") + 1) : tagName;
        if (hasChildren && !cleanTagName.startsWith("ListOf")) {
            seenTags.add(cleanTagName);
        }

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                collectParentTags((Element) child, seenTags);
            }
        }
    }

    private void buildTreeNodes(Node node, DefaultMutableTreeNode treeNode, String currentPath, String immediateParent, boolean isTriggered, String startTag) {
        NodeList children = node.getChildNodes();
        Set<String> processedChildNames = new HashSet<>();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String tagName = child.getNodeName();
                // Нормализуем для отображения в дереве
                String displayTagName = tagName.contains(":") ? tagName.substring(tagName.lastIndexOf(":") + 1) : tagName;
                String cleanTagName = tagName.contains(":") ? tagName.substring(tagName.lastIndexOf(":") + 1) : tagName;

                // Если родитель называется ListOf - обрабатываем только один элемент с данным именем
                String parentName = node.getNodeName();
                String cleanParentName = parentName.contains(":") ? parentName.substring(parentName.lastIndexOf(":") + 1) : parentName;
                if (cleanParentName.startsWith("ListOf") && processedChildNames.contains(cleanTagName)) {
                    continue;
                }
                processedChildNames.add(cleanTagName);

                String newPath = currentPath;
                String nextImmediateParent = immediateParent;
                boolean nextTriggered = isTriggered;

                if (!startTag.isEmpty() && !isTriggered) {
                    if (cleanTagName.equals(startTag)) {
                        nextTriggered = true;
                        newPath = startTag;
                        nextImmediateParent = cleanTagName;
                    }
                } else {
                    if (isTriggered) {
                        newPath = currentPath + "/" + cleanTagName;
                    } else {
                        newPath = currentPath.isEmpty() ? cleanTagName : currentPath + "/" + cleanTagName;
                    }
                    if (isTriggered || startTag.isEmpty()) {
                        if (!cleanTagName.startsWith("ListOf")) {
                            nextImmediateParent = cleanTagName;
                        }
                    }
                }

                boolean hasChildElements = false;
                NodeList subChildren = child.getChildNodes();
                for (int j = 0; j < subChildren.getLength(); j++) {
                    if (subChildren.item(j).getNodeType() == Node.ELEMENT_NODE) {
                        hasChildElements = true;
                        break;
                    }
                }

                CheckboxNode childData = new CheckboxNode(displayTagName, newPath, immediateParent, false);
                childData.isLeafField = !hasChildElements;
                childData.isTriggeredNode = nextTriggered;
                childData.parentTagName = node.getNodeName();
                if (nextTriggered) {
                    childData.setStartTag(startTag);
                }

                DefaultMutableTreeNode childTreeNode = new DefaultMutableTreeNode(childData);
                treeNode.add(childTreeNode);

                // Атрибуты добавляем как дочерние к childTreeNode
                if (child.hasAttributes()) {
                    NamedNodeMap attributes = child.getAttributes();
                    for (int a = 0; a < attributes.getLength(); a++) {
                        Node attr = attributes.item(a);
                        String attrName = attr.getNodeName();
                        if (attrName.startsWith("xmlns")) continue;
                        String cleanAttrName = attrName.contains(":") ? attrName.substring(attrName.lastIndexOf(":") + 1) : attrName;
                        String attrPath = newPath + "/@" + cleanAttrName;
                        CheckboxNode attrData = new CheckboxNode("@" + attrName, attrPath, immediateParent, false);
                        attrData.isLeafField = true;
                        attrData.isAttribute = true;
                        attrData.isTriggeredNode = nextTriggered;
                        if (nextTriggered) {
                            attrData.setStartTag(startTag);
                        }
                        DefaultMutableTreeNode attrTreeNode = new DefaultMutableTreeNode(attrData);
                        childTreeNode.add(attrTreeNode);
                    }
                }

                buildTreeNodes(child, childTreeNode, newPath, nextImmediateParent, nextTriggered, startTag);
            }
        }
    }

    private void generateCodeFromSelected() {
        seenXpaths.clear();
        List<String> fieldsOutput = new ArrayList<>();
        List<String> variablesOutput = new ArrayList<>();

        DefaultMutableTreeNode treeRoot = (DefaultMutableTreeNode) treeModel.getRoot();
        collectSelectedFields(treeRoot, fieldsOutput, variablesOutput);

        StringBuilder sb = new StringBuilder();
        sb.append("<!-- ====================================================================== -->\n");
        sb.append("<!-- СЕКЦИЯ ПОЛЕЙ ДЛЯ ВСТАВКИ В .JRXML                                      -->\n");
        sb.append("<!-- ====================================================================== -->\n");
        for (String fieldStr : fieldsOutput) {
            sb.append(fieldStr).append("\n");
        }

        if (!variablesOutput.isEmpty()) {
            sb.append("\n<!-- ====================================================================== -->\n");
            sb.append("<!-- СЕКЦИЯ ПЕРЕМЕННЫХ ДЛЯ ВСТАВКИ В .JRXML                                 -->\n");
            sb.append("<!-- ====================================================================== -->\n");
            for (String varStr : variablesOutput) {
                sb.append(varStr).append("\n");
            }
        }

        outputTextArea.setText(sb.toString());
        outputTextArea.setCaretPosition(0);

        boolean hasContent = !sb.toString().isEmpty();
        copyButton.setEnabled(hasContent);
    }

    private void collectSelectedFields(DefaultMutableTreeNode treeNode, List<String> fieldsOutput, List<String> variablesOutput) {
        collectSelectedFields(treeNode, fieldsOutput, variablesOutput, new ArrayList<>());
    }

    private void collectSelectedFields(DefaultMutableTreeNode treeNode, List<String> fieldsOutput, List<String> variablesOutput, List<CheckboxNode.FilterEntry> ancestorFilters) {
        CheckboxNode checkNode = (CheckboxNode) treeNode.getUserObject();

        // Собираем фильтры от этого узла
        List<CheckboxNode.FilterEntry> currentFilters = new ArrayList<>(ancestorFilters);
        currentFilters.addAll(checkNode.filters);

        if (checkNode.isSelected && checkNode.isLeafField && checkNode.isTriggeredNode) {
            String finalXpath = checkNode.buildFilteredXpathWithAncestors(currentFilters);
            boolean hasFilters = checkNode.hasFilters() || !ancestorFilters.isEmpty();

            if (!seenXpaths.contains(finalXpath)) {
                seenXpaths.add(finalXpath);

                String cleanNodeName = checkNode.nodeName.replace("@", "");
                String prefix = (checkNode.parentPrefix != null && !checkNode.parentPrefix.isEmpty())
                        ? checkNode.parentPrefix
                        : "";
                String fieldNameWithPrefix = prefix.isEmpty()
                        ? cleanNodeName
                        : prefix + "_" + cleanNodeName;

                if (checkNode.isAttribute) {
                    fieldNameWithPrefix += "_attr";
                }

                String fieldXml;
                if (hasFilters) {
                    fieldXml = "    <field name=\"" + fieldNameWithPrefix + "\" class=\"java.lang.String\">\n" +
                            "        <property name=\"net.sf.jasperreports.xpath.field.expression\">\n" +
                            "            <![CDATA[" + finalXpath + "]]>\n" +
                            "        </property>\n" +
                            "    </field>";
                } else {
                    fieldXml = "    <field name=\"" + fieldNameWithPrefix + "\" class=\"java.lang.String\">\n" +
                            "        <property name=\"net.sf.jasperreports.xpath.field.expression\" value=\"" + finalXpath + "\"/>\n" +
                            "    </field>";
                }
                fieldsOutput.add(fieldXml);

                // Генерация переменных для дат (только для обычных полей, содержащих Date)
                if (checkNode.nodeName.toLowerCase().contains("date")) {
                    String varXml = "    <variable name=\"" + fieldNameWithPrefix + "\" class=\"java.lang.String\">\n" +
                            "        <variableExpression><![CDATA[$F{" + fieldNameWithPrefix + "} != null && $F{" + fieldNameWithPrefix + "}.trim().length() >= 10 ? " +
                            "( $F{" + fieldNameWithPrefix + "}.trim().length() > 10 ? " +
                            "new java.text.SimpleDateFormat(\"dd.MM.yyyy HH:mm:ss\").format(new java.text.SimpleDateFormat(\"MM/dd/yyyy HH:mm:ss\").parse($F{" + fieldNameWithPrefix + "}.trim())) : " +
                            "new java.text.SimpleDateFormat(\"dd.MM.yyyy\").format(new java.text.SimpleDateFormat(\"MM/dd/yyyy\").parse($F{" + fieldNameWithPrefix + "}.trim())) ) : \"\"]]></variableExpression>\n" +
                            "    </variable>";
                    variablesOutput.add(varXml);
                }
            }
        }

        int childCount = treeNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            collectSelectedFields((DefaultMutableTreeNode) treeNode.getChildAt(i), fieldsOutput, variablesOutput, currentFilters);
        }
    }

    static class CheckboxNode {
        String nodeName;
        String xpathPath;
        String parentPrefix;
        String parentTagName = "";
        boolean isSelected;
        boolean isLeafField = false;
        boolean isTriggeredNode = false;
        boolean isAttribute = false;

        // Храним фильтр вместе с именем узла-предка, к которому он относится
        List<FilterEntry> filters = new ArrayList<>();
        String startTag = "";

        static class FilterEntry {
            String condition;
            String ancestorName; // имя узла, на который наложен фильтр

            FilterEntry(String condition, String ancestorName) {
                this.condition = condition;
                this.ancestorName = ancestorName;
            }
        }

        public CheckboxNode(String nodeName, String xpathPath, String parentPrefix, boolean isSelected) {
            this.nodeName = nodeName;
            this.xpathPath = xpathPath;
            this.parentPrefix = parentPrefix;
            this.isSelected = isSelected;
        }

        public void setStartTag(String startTag) {
            this.startTag = startTag;
        }

        public void addFilter(String condition, String ancestorName) {
            filters.add(new FilterEntry(condition, ancestorName));
        }

        public boolean hasFilters() {
            return !filters.isEmpty();
        }

        public String buildFilteredXpath() {
            return buildFilteredXpathWithAncestors(filters);
        }

        public String buildFilteredXpathWithAncestors(List<FilterEntry> ancestorFilters) {
            String[] pathParts = xpathPath.split("/");

            // Находим индекс стартового тега в пути
            int startIndex = 0;
            if (!startTag.isEmpty()) {
                // Ищем ПОСЛЕДНЕЕ вхождение startTag в пути
                for (int i = pathParts.length - 1; i >= 0; i--) {
                    if (pathParts[i].replace("@", "").equals(startTag)) {
                        startIndex = i;
                        break;
                    }
                }
            } else if (!ancestorFilters.isEmpty()) {
                // Если startTag не установлен, берём первый ancestor
                String firstAncestor = ancestorFilters.get(0).ancestorName;
                for (int i = 0; i < pathParts.length; i++) {
                    if (pathParts[i].replace("@", "").equals(firstAncestor)) {
                        startIndex = i;
                        break;
                    }
                }
            }

            // К каждому сегменту пути (начиная с первого ancestor) собираем список предикатов
            Map<Integer, List<String>> predicatesByPart = new LinkedHashMap<>();
            for (int i = startIndex; i < pathParts.length; i++) {
                predicatesByPart.put(i, new ArrayList<>());
            }

            for (CheckboxNode.FilterEntry filterEntry : ancestorFilters) {
                String predicate = filterEntry.condition;
                String ancestorName = filterEntry.ancestorName;

                // Ищем индекс ancestor-узла в пути
                int targetPart = -1;
                for (int i = startIndex; i < pathParts.length; i++) {
                    if (pathParts[i].replace("@", "").equals(ancestorName)) {
                        targetPart = i;
                        break;
                    }
                }

                // Если ancestor не найден — применяем к последнему элементу пути
                if (targetPart == -1) {
                    for (int i = pathParts.length - 1; i >= startIndex; i--) {
                        if (!pathParts[i].startsWith("@")) {
                            targetPart = i;
                            break;
                        }
                    }
                    if (targetPart == -1) targetPart = startIndex;
                }

                predicatesByPart.get(targetPart).add(predicate);
            }

            // Собираем итоговый XPath, начиная с ancestor-а (стартового тега)
            StringBuilder result = new StringBuilder("//");
            for (int i = startIndex; i < pathParts.length; i++) {
                if (i > startIndex) result.append("/");
                result.append(pathParts[i]);
                List<String> preds = predicatesByPart.get(i);
                if (!preds.isEmpty()) {
                    result.append("[").append(String.join(" and ", preds)).append("]");
                }
            }

            return result.toString();
        }

        @Override
        public String toString() {
            return nodeName;
        }
    }

    static class CheckboxNodeRenderer extends JPanel implements TreeCellRenderer {
        private final JCheckBox checkBox;
        private final JLabel label;

        public CheckboxNodeRenderer() {
            setLayout(new BorderLayout());
            setOpaque(false);
            checkBox = new JCheckBox();
            checkBox.setOpaque(false);
            label = new JLabel();
            label.setFont(new Font("Arial", Font.PLAIN, 12));
            add(checkBox, BorderLayout.WEST);
            add(label, BorderLayout.CENTER);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                      boolean expanded, boolean leaf, int row, boolean hasFocus) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object userObj = node.getUserObject();

            if (userObj instanceof CheckboxNode) {
                CheckboxNode checkNode = (CheckboxNode) userObj;
                checkBox.setVisible(true);
                checkBox.setSelected(checkNode.isSelected);
                checkBox.setEnabled(checkNode.isTriggeredNode);
                label.setText(checkNode.nodeName);

                if (!checkNode.isTriggeredNode) {
                    label.setForeground(new Color(169, 169, 169));
                    label.setFont(new Font("Arial", Font.BOLD, 12));
                } else if (checkNode.isAttribute) {
                    label.setFont(new Font("Arial", Font.BOLD, 12));
                    label.setForeground(new Color(0, 128, 64));
                } else if (checkNode.isLeafField) {
                    label.setFont(new Font("Arial", Font.ITALIC | Font.BOLD, 12));
                    label.setForeground(new Color(0, 102, 204));
                } else {
                    label.setFont(new Font("Arial", Font.BOLD, 12));
                    label.setForeground(Color.BLACK);
                }

                if (checkNode.hasFilters()) {
                    int filterCount = checkNode.filters.size();
                    label.setText(checkNode.nodeName + " [" + filterCount + "]");
                }
            } else {
                checkBox.setVisible(false);
                label.setText(value.toString());
            }
            return this;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new JasperGeneratorApp().setVisible(true);
        });
    }
}