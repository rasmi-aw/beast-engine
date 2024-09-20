package com.beastwall.beastengine;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BeastHtmlEngine extends BeastEngine {

    // Constructor initializing the ScriptEngine for Nashorn
    public BeastHtmlEngine() {
        super();
    }

    public BeastHtmlEngine(String componentsPath) {
        super(componentsPath);
    }

    @Override
    public String process(String template, Map<String, Object> context) throws Exception {
        // Check if the template is precompiled and cached
        String output;
        try {
            Document doc = Jsoup.parse(template);
            StringBuilder result = new StringBuilder();
            context.keySet().parallelStream().forEach(k -> {
                engine.get().put(k, context.get(k));
            });
            processNode(doc.child(0), context, result, "");
            output = result.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error rendering template", e);
        }
        clearCache();
        return output;
    }

    @Override
    public String processComponent(String componentName, Map<String, Object> context) throws Exception {
        String res = context.get(componentName + ".component" + componentExtension()).toString();
        if (res == null) {
            res = process(readComponent(componentName), context);
            components.put(componentName + ".component" + componentExtension(), res);
        }
        return res;
    }

    // Updated processNode method for bs:if handling
    private void processNode(Node node, Map<String, Object> context, StringBuilder result, String scopeIdentifier) throws Exception {
        // process attributes

        // process node
        if (node instanceof TextNode) {
            processText(((TextNode) node).text(), context, result, scopeIdentifier);
        } else if (node instanceof Element) {
            Element element = (Element) node;
            String tagName = element.tagName();

            switch (tagName) {
                case TAG_PREFIX + "var":
                    Arrays.stream(element.ownText().split(";")).forEachOrdered(expression -> {
                        String[] ex = expression.split("=");
                        String var = ex[0].trim();
                        String value = ex[1].trim();
                        Object val;
                        try {
                            val = engine.get().eval(value);
                            context.put(var, val);
                            engine.get().put(var, val);
                        } catch (ScriptException e) {
                            context.put(var, value);
                            engine.get().put(var, value);
                        }
                    });

                    break;
                case TAG_PREFIX + "if":
                    String condition = element.attr("condition");
                    if (evaluateCondition(condition, context, scopeIdentifier)) {
                        processChildren(element, context, result, scopeIdentifier);
                    }
                    break;
                case TAG_PREFIX + "switch":
                    processSwitch(element, context, result, scopeIdentifier);
                    break;
                case TAG_PREFIX + "for":
                    processForLoop(element, context, result, scopeIdentifier);
                    break;
                case TAG_PREFIX + "repeat":
                    processRepeat(element, context, result, scopeIdentifier);
                    break;
                case TAG_PREFIX + "component":
                    String componentName = element.attr("name");
                    result.append(renderComponent(componentName, context, scopeIdentifier, element.attributes().hasKey("static")));
                    break;
                default:
                    result.append("<").append(tagName);
                    for (Attribute attr : element.attributes()) {
                        if (attr.getKey().startsWith(TAG_PREFIX)) {
                            result.append(" ").append(attr.getKey().replace(TAG_PREFIX, "")).append("=\"").append(eval(attr.getValue(), context, scopeIdentifier)).append("\"");
                        } else
                            result.append(" ").append(attr.getKey()).append("=\"").append(attr.getValue()).append("\"");
                    }
                    result.append(">");
                    processChildren(element, context, result, scopeIdentifier);
                    result.append("</").append(tagName).append(">");
            }
        }
    }

    private void processChildren(Element element, Map<String, Object> context, StringBuilder result, String scopeIdentifier) throws Exception {
        for (Node child : element.childNodes()) {
            processNode(child, context, result, scopeIdentifier);
        }
    }

    private void processSwitch(Element element, Map<String, Object> context, StringBuilder result, String scopeIdentifier) throws Exception {
        String switchVar = element.attr("var");
        Object switchValue = resolveVariableCached(switchVar, context, scopeIdentifier);
        if (switchValue == null) {
            throw new RuntimeException("Switch value cannot be null");
        }

        boolean matched = false;
        List<Element> caseElements = element.getElementsByTag(TAG_PREFIX + "case");
        for (Element caseElement : caseElements) {
            String matchAttr = caseElement.attr("match");
            if (matchAttr.equals(switchValue.toString())) {
                processChildren(caseElement, context, result, scopeIdentifier);
                matched = true;
                break;
            }
        }

        if (!matched) {
            Element defaultElement = element.getElementsByTag(TAG_PREFIX + "default").first();
            if (defaultElement != null) {
                processChildren(defaultElement, context, result, scopeIdentifier);
            }
        }
    }

    private void processForLoop(Element element, Map<String, Object> context, StringBuilder result, String scopeIdentifier) throws Exception {
        String itemName = element.attr("item");
        String listName = element.attr("in");
        Object listObj = resolveVariableCached(listName, context, scopeIdentifier);

        if (listObj instanceof List) {
            List<?> items = (List<?>) listObj;
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                Map<String, Object> loopContext = new HashMap<>(context);
                loopContext.put(itemName, item);
                engine.get().put(itemName, item);
                String loopScopeIdentifier = scopeIdentifier + "_" + listName + "_" + i;
                processChildren(element, loopContext, result, loopScopeIdentifier);
            }
        }
    }

    private void processRepeat(Element element, Map<String, Object> context, StringBuilder result, String scopeIdentifier) throws Exception {
        // Get the number of times to repeat
        Object timesVal = element.attr("times");
        int times;
        try {
            times = Integer.parseInt(((String) timesVal));
        } catch (Exception e) {
            try {
                timesVal = resolveVariableCached(((String) timesVal), context, scopeIdentifier);
                times = ((Integer) timesVal);
            } catch (Exception ex) {
                throw new RuntimeException("error parsing variable: " + element.attr("times"), ex);
            }
        }
        // Repeat the content 'times' number of times
        for (int i = 0; i < times; i++) {
            // Pass a unique scope identifier for each iteration
            processChildren(element, context, result, scopeIdentifier + "_" + i);
        }
    }


    private String renderComponent(String componentName, Map<String, Object> context, String scopeIdentifier, boolean isStatic) throws Exception {
        String result = null;
        if (isStatic) {
            result = components.get(componentName);
        }
        //
        if (result == null) {
            try {
                String componentTemplate = readComponent(componentName);
                Document componentDoc = Jsoup.parse(componentTemplate);
                StringBuilder componentResult = new StringBuilder();
                for (Node child : componentDoc.body().childNodes()) {
                    processNode(child, context, componentResult, scopeIdentifier + "_" + componentResult);
                }
                result = componentResult.toString();
                if (isStatic)
                    components.put(componentName, result);
                return result;
            } catch (Exception e) {
                throw new RuntimeException("Error rendering component: " + components, e);
            }
        }
        return result;
    }

    private boolean evaluateExpression(String expression) {
        try {
            ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
            if (engine == null) {
                throw new RuntimeException("Nashorn JavaScript engine not found");
            }
            Object result = engine.eval(expression);
            return Boolean.parseBoolean(result.toString());
        } catch (ScriptException e) {
            throw new RuntimeException("Error evaluating expression: " + expression, e);
        }
    }


    @Override
    public String componentExtension() {
        return ".html";
    }
}
