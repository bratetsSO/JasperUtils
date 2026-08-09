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
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class JasperGeneratorApp extends JFrame {

    private JTextField fileTextField;
    private JTextField tagTextField;
    private JTextArea outputTextArea;
    private JButton loadStructureButton;
    private JButton generateButton;
    private JButton browseButton;
    private JButton copyButton;
    private JButton saveToFileButton;

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
        inputPanel.add(new JLabel("Путь к XML-файлу Siebel IO:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        fileTextField = new JTextField();
        inputPanel.add(fileTextField, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        browseButton = new JButton("Обзор...");
        inputPanel.add(browseButton, gbc);

        // Строка 2: Настройка стартового тега
        gbc.gridx = 0; gbc.gridy = 1;
        inputPanel.add(new JLabel("Название стартового тега:"), gbc);

        gbc.gridx = 1; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        tagTextField = new JTextField("K7mOpportunityPowersConfirmationNonui");
        inputPanel.add(tagTextField, gbc);

        // Строка 3: Кнопки управления структурой
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3;
        JPanel buttonsPanel = new JPanel(new GridLayout(1, 2, 10, 0));
        loadStructureButton = new JButton("1. Загрузить структуру XML");
        loadStructureButton.setFont(new Font("Arial", Font.BOLD, 12));
        generateButton = new JButton("2. Сгенерировать код для .JRXML");
        generateButton.setFont(new Font("Arial", Font.BOLD, 12));
        generateButton.setEnabled(false);
        buttonsPanel.add(loadStructureButton);
        buttonsPanel.add(generateButton);
        inputPanel.add(buttonsPanel, gbc);

        // Центральная часть: Сплит-панель (Дерево слева, Результат справа)
        rootCheckboxNode = new CheckboxNode("Структура не загружена", "", "", false);
        DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode(rootCheckboxNode);
        treeModel = new DefaultTreeModel(treeRoot);
        xmlTree = new JTree(treeModel);
        xmlTree.setCellRenderer(new CheckboxNodeRenderer());
        xmlTree.setSelectionRow(0);

        xmlTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = xmlTree.getRowForLocation(e.getX(), e.getY());
                if (row != -1) {
                    TreePath path = xmlTree.getPathForLocation(e.getX(), e.getY());
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                    CheckboxNode checkNode = (CheckboxNode) node.getUserObject();
                    boolean nextState = !checkNode.isSelected;

                    toggleNodeSelection(node, nextState);
                    treeModel.nodeChanged(node);
                    xmlTree.repaint();
                }
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

        // Нижняя панель действий
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        copyButton = new JButton("Копировать в буфер обмена");
        copyButton.setFont(new Font("Arial", Font.BOLD, 12));
        copyButton.setEnabled(false);

        saveToFileButton = new JButton("Сохранить в файл...");
        saveToFileButton.setFont(new Font("Arial", Font.BOLD, 12));
        saveToFileButton.setEnabled(false);

        actionPanel.add(copyButton);
        actionPanel.add(saveToFileButton);

        // Компоновка
        setLayout(new BorderLayout());
        add(inputPanel, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);
        add(addSelectButtonsPanel(treeRoot), BorderLayout.WEST);
        add(actionPanel, BorderLayout.SOUTH);

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

        saveToFileButton.addActionListener(e -> saveOutputToFile());
    }

    private JPanel addSelectButtonsPanel(DefaultMutableTreeNode treeRoot) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        gbc.insets = new Insets(2, 2, 2, 2);

        JButton selectAllBtn = new JButton("Выбрать всё");
        selectAllBtn.addActionListener(e -> {
            toggleNodeSelection(treeRoot, true);
            xmlTree.repaint();
        });
        panel.add(selectAllBtn, gbc);

        gbc.gridy = 1;
        JButton deselectAllBtn = new JButton("Снять всё");
        deselectAllBtn.addActionListener(e -> {
            toggleNodeSelection(treeRoot, false);
            xmlTree.repaint();
        });
        panel.add(deselectAllBtn, gbc);

        return panel;
    }

    private void toggleNodeSelection(DefaultMutableTreeNode node, boolean isSelected) {
        CheckboxNode checkNode = (CheckboxNode) node.getUserObject();
        checkNode.isSelected = isSelected;
        treeModel.nodeChanged(node);

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            toggleNodeSelection((DefaultMutableTreeNode) node.getChildAt(i), isSelected);
        }
    }

    private void parseXmlStructure() {
        String filePath = fileTextField.getText().trim();
        String startTag = tagTextField.getText().trim();

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

            rootCheckboxNode = new CheckboxNode(currentDoc.getDocumentElement().getNodeName(), "", "", true);
            treeRoot.setUserObject(rootCheckboxNode);

            buildTreeNodes(currentDoc.getDocumentElement(), treeRoot, "", "", false, startTag);

            treeModel.reload();
            for (int i = 0; i < xmlTree.getRowCount(); i++) {
                xmlTree.expandRow(i);
            }

            generateButton.setEnabled(true);
            outputTextArea.setText("");
            copyButton.setEnabled(false);
            saveToFileButton.setEnabled(false);
            JOptionPane.showMessageDialog(this, "Структура XML успешно загружена!", "Успех", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Ошибка разбора структуры:\n" + ex.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
            ex.printStackTrace();
        }
    }

    private void buildTreeNodes(Node node, DefaultMutableTreeNode treeNode, String currentPath, String immediateParent, boolean isTriggered, String startTag) {
        // Проверяем наличие атрибутов у текущего тега
        if (node.hasAttributes()) {
            NamedNodeMap attributes = node.getAttributes();
            for (int a = 0; a < attributes.getLength(); a++) {
                Node attr = attributes.item(a);
                String attrName = attr.getNodeName();

                // Пропускаем встроенные пространства имен xmlns, если они есть
                if (attrName.startsWith("xmlns") || attrName.contains(":")) continue;

                // Путь XPath для атрибута формируется через /@имя_атрибута
                String attrPath = currentPath + "/@" + attrName;

                CheckboxNode attrData = new CheckboxNode("@" + attrName, attrPath, immediateParent, true);
                attrData.isLeafField = true;
                attrData.isAttribute = true;
                attrData.isTriggeredNode = (startTag.isEmpty() || isTriggered);

                DefaultMutableTreeNode attrTreeNode = new DefaultMutableTreeNode(attrData);
                treeNode.add(attrTreeNode);
            }
        }

        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String tagName = child.getNodeName();
                String newPath = currentPath;
                String nextImmediateParent = immediateParent;
                boolean nextTriggered = isTriggered;

                if (!startTag.isEmpty() && !isTriggered) {
                    if (tagName.equals(startTag)) {
                        nextTriggered = true;
                        newPath = tagName;
                        nextImmediateParent = tagName;
                    }
                } else {
                    newPath = currentPath.isEmpty() ? tagName : currentPath + "/" + tagName;
                    if (isTriggered || startTag.isEmpty()) {
                        if (!tagName.startsWith("ListOf")) {
                            nextImmediateParent = tagName;
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

                CheckboxNode childData = new CheckboxNode(tagName, newPath, nextImmediateParent, true);
                childData.isLeafField = !hasChildElements;
                childData.isTriggeredNode = (startTag.isEmpty() || nextTriggered);

                DefaultMutableTreeNode childTreeNode = new DefaultMutableTreeNode(childData);
                treeNode.add(childTreeNode);

                // Рекурсивно идем вглубь (передаем дочерний элемент для анализа его детей и атрибутов)
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
            sb.append("<!-- СЕКЦИЯ ПЕРЕМЕННЫХ ДЛЯ ВСТАВКИ В .JRXML                                 -->");
                    sb.append("<!-- ====================================================================== -->\n");
            for (String varStr : variablesOutput) {
                sb.append(varStr).append("\n");
            }
        }

        outputTextArea.setText(sb.toString());
        outputTextArea.setCaretPosition(0);

        boolean hasContent = !sb.toString().isEmpty();
        copyButton.setEnabled(hasContent);
        saveToFileButton.setEnabled(hasContent);
    }

    private void collectSelectedFields(DefaultMutableTreeNode treeNode, List<String> fieldsOutput, List<String> variablesOutput) {
        CheckboxNode checkNode = (CheckboxNode) treeNode.getUserObject();

        if (checkNode.isSelected && checkNode.isLeafField && checkNode.isTriggeredNode) {
            String finalXpath = "//" + checkNode.xpathPath;

            if (!seenXpaths.contains(finalXpath)) {
                seenXpaths.add(finalXpath);

                // Формируем имя поля. Если это атрибут, убираем символ @ из названия переменной, чтобы .jrxml был валидным
                String cleanNodeName = checkNode.nodeName.replace("@", "");
                String fieldNameWithPrefix = checkNode.parentPrefix + "_" + cleanNodeName;

                // Добавляем маркер _attr к имени поля, если это атрибут тега, для предотвращения пересечений имен
                if (checkNode.isAttribute) {
                    fieldNameWithPrefix += "_attr";
                }

                String fieldXml = "    <field name=\"" + fieldNameWithPrefix + "\" class=\"java.lang.String\">\n" +
                        "        <property name=\"net.sf.jasperreports.xpath.field.expression\" value=\"" + finalXpath + "\"/>\n" +
                        "    </field>";
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
            collectSelectedFields((DefaultMutableTreeNode) treeNode.getChildAt(i), fieldsOutput, variablesOutput);
        }
    }

    private void saveOutputToFile() {
        String content = outputTextArea.getText();
        if (content.isEmpty()) return;

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Сохранить XML код полей");
        fileChooser.setSelectedFile(new File("jasper_fields.xml"));
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("XML файлы (*.xml, *.txt)", "xml", "txt"));

        int userSelection = fileChooser.showSaveDialog(this);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile();
            if (!fileToSave.getName().contains(".")) {
                fileToSave = new File(fileToSave.getAbsolutePath() + ".xml");
            }

            try (FileWriter writer = new FileWriter(fileToSave)) {
                writer.write(content);
                JOptionPane.showMessageDialog(this, "Файл успешно сохранен:\n" + fileToSave.getAbsolutePath(), "Успех", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Не удалось сохранить файл:\n" + ex.getMessage(), "Ошибка записи", JOptionPane.ERROR_MESSAGE);
                ex.printStackTrace();
            }
        }
    }

    static class CheckboxNode {
        String nodeName;
        String xpathPath;
        String parentPrefix;
        boolean isSelected;
        boolean isLeafField = false;
        boolean isTriggeredNode = false;
        boolean isAttribute = false; // Новое свойство-маркер для атрибутов

        public CheckboxNode(String nodeName, String xpathPath, String parentPrefix, boolean isSelected) {
            this.nodeName = nodeName;
            this.xpathPath = xpathPath;
            this.parentPrefix = parentPrefix;
            this.isSelected = isSelected;
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
                label.setText(checkNode.nodeName);

                if (checkNode.isAttribute) {
                    // Атрибуты выделяем темно-зеленым цветом со сдвигом шрифта
                    label.setFont(new Font("Arial", Font.BOLD, 12));
                    label.setForeground(new Color(0, 128, 64));
                } else if (checkNode.isLeafField) {
                    // Конечные теги-поля выделяем синим цветом
                    label.setFont(new Font("Arial", Font.ITALIC | Font.BOLD, 12));
                    label.setForeground(new Color(0, 102, 204));
                } else {
                    // Компоненты-родители остаются стандартными
                    label.setFont(new Font("Arial", Font.BOLD, 12));
                    label.setForeground(Color.BLACK);
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