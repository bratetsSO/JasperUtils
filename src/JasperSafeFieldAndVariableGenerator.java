import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

public class JasperSafeFieldAndVariableGenerator {

    private static final Set<String> seenXpaths = new HashSet<>();

    // НАСТРОЙКА: Укажите стартовый тег (или оставьте "", чтобы считать от самого корня)
    private static final String START_TAG = "ListOfBpsCorpK7mPrintForms";

    public static void main(String[] args) {
        // Укажите путь к вашему файлу с примером выгрузки XML из Siebel IO
        String xmlFilePath = "F:\\work\\CORP_K7M_DOG_0001.xml";

        try {
            File inputFile = new File(xmlFilePath);
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            dbFactory.setNamespaceAware(false); // Отключаем namespaces для чистого XPath
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(inputFile);
            doc.getDocumentElement().normalize();

            // Списки для раздельного накопления полей и переменных
            List<String> fieldsOutput = new ArrayList<>();
            List<String> variablesOutput = new ArrayList<>();

            // Запуск рекурсивного обхода структуры XML
            walkNode(doc.getDocumentElement(), "", "", false, fieldsOutput, variablesOutput);

            // Вывод результата в консоль для копирования
            System.out.println("<!-- ====================================================================== -->");
            System.out.println("<!-- СЕКЦИЯ ПОЛЕЙ ДЛЯ ВСТАВКИ В .JRXML (ПОД СЕКЦИЕЙ <queryString>)          -->");
            System.out.println("<!-- ====================================================================== -->");
            for (String fieldStr : fieldsOutput) {
                System.out.println(fieldStr);
            }

            if (!variablesOutput.isEmpty()) {
                System.out.println("\n<!-- ====================================================================== -->");
                System.out.println("<!-- СЕКЦИЯ ПЕРЕМЕННЫХ ДЛЯ ВСТАВКИ В .JRXML (ПОД СЕКЦИЕЙ ПОЛЕЙ)             -->");
                System.out.println("<!-- ====================================================================== -->");
                for (String varStr : variablesOutput) {
                    System.out.println(varStr);
                }
            }

        } catch (Exception e) {
            System.err.println("Ошибка при анализе XML: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void walkNode(Node node, String currentPath, String immediateParent, boolean isTriggered,
                                 List<String> fieldsOutput, List<String> variablesOutput) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            String tagName = node.getNodeName();

            String newPath = currentPath;
            String nextImmediateParent = immediateParent;
            boolean nextTriggered = isTriggered;

            // Логика определения стартового тега
            if (!START_TAG.isEmpty() && !isTriggered) {
                if (tagName.equals(START_TAG)) {
                    nextTriggered = true;
                    newPath = tagName;
                    nextImmediateParent = tagName; // Стартовый тег становится первым родителем
                }
            } else {
                newPath = currentPath.isEmpty() ? tagName : currentPath + "/" + tagName;
                if (isTriggered || START_TAG.isEmpty()) {
                    // Игнорируем технические обертки Siebel "ListOf...", берем только значащих предков
                    if (!tagName.startsWith("ListOf")) {
                        nextImmediateParent = tagName;
                    }
                }
            }

            // Проверяем наличие дочерних элементов (является ли узел конечным атрибутом)
            boolean hasChildElements = false;
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                    hasChildElements = true;
                    break;
                }
            }

            // Если это конечное текстовое поле (поле данных Siebel)
            if (!hasChildElements) {
                if (START_TAG.isEmpty() || nextTriggered) {
                    // Требование: всегда добавлять двойной слэш "//" в начале итогового пути
                    String finalXpath = "//" + newPath;

                    if (!seenXpaths.contains(finalXpath)) {
                        seenXpaths.add(finalXpath);

                        // Требование: имя поля формируется всегда как ЕдинственныйПредок_ИмяПоля
                        String fieldNameWithPrefix = immediateParent + "_" + tagName;

                        // Формируем XML для тега <field>
                        String fieldXml = "    <field name=\"" + fieldNameWithPrefix + "\" class=\"java.lang.String\">\n" +
                                "        <property name=\"net.sf.jasperreports.xpath.field.expression\" value=\"" + finalXpath + "\"/>\n" +
                                "    </field>";
                        fieldsOutput.add(fieldXml);

                        // ТРЕБОВАНИЕ ДЛЯ ДАТ: Если в оригинальном имени тега содержится "Date"
                        if (tagName.toLowerCase().contains("date")) {
                            // Формируем универсальное выражение, сохраняющее время, если оно пришло
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
            } else {
                // Идем глубже по дереву XML
                for (int i = 0; i < children.getLength(); i++) {
                    walkNode(children.item(i), newPath, nextImmediateParent, nextTriggered, fieldsOutput, variablesOutput);
                }
            }
        }
    }
}
