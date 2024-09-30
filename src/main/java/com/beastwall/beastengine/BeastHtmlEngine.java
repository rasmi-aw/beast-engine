package com.beastwall.beastengine;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BeastHtmlEngine extends BeastEngine {

    // Constructor initializing the ScriptEngine for Nashorn
    public BeastHtmlEngine() {
        super();
    }

    public BeastHtmlEngine(String componentsPath) {
        super(componentsPath);
    }

    @Override
    public String process(String template, Context context) throws Exception {
        // Check if the template is precompiled and cached
        String output;
        try {
            Document doc = Jsoup.parse(template);
            StringBuilder result = new StringBuilder();
            ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
            context.keySet().forEach(k -> {
                engine.put(k, context.get(k));
            });
            processNode(doc.child(0), context, result, "", new HashMap<>(), engine);
            output = result.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error rendering template", e);
        }
        return output;
    }

    @Override
    public String processComponent(String componentName, Context context) throws Exception {
        String res = components.get("static:" + context.getLocale().getLanguage() + ":" + componentName + ".component" + componentExtension());
        if (res != null)
            return res;
        // case the components is not static
        res = process(readComponent(componentName), context);

        return res;
    }

    // Updated processNode method for bs:if handling
    private void processNode(Node node, Context context, StringBuilder result, String scopeIdentifier, Map<String, Object> resolvedVariables, ScriptEngine engine) throws Exception {
        // process attributes

        // process node
        if (node instanceof TextNode) {
            processText(((TextNode) node).text(), context, result, scopeIdentifier, resolvedVariables, engine);
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
                            val = engine.eval(value);
                            context.put(var, val);
                            engine.put(var, val);
                        } catch (ScriptException e) {
                            context.put(var, value);
                            engine.put(var, value);
                        }
                    });

                    break;
                case TAG_PREFIX + "if":
                    String condition = element.attr("condition");
                    if (evaluateCondition(condition, context, scopeIdentifier, resolvedVariables, engine)) {
                        processChildren(element, context, result, scopeIdentifier, resolvedVariables, engine);
                    }
                    break;
                case TAG_PREFIX + "switch":
                    processSwitch(element, context, result, scopeIdentifier, resolvedVariables, engine);
                    break;
                case TAG_PREFIX + "for":
                    processForLoop(element, context, result, scopeIdentifier, resolvedVariables, engine);
                    break;
                case TAG_PREFIX + "repeat":
                    processRepeat(element, context, result, scopeIdentifier, resolvedVariables, engine);
                    break;
                case TAG_PREFIX + "component":
                    String componentName = element.attr("name");
                    result.append(renderComponent(componentName, context, scopeIdentifier, element.attributes().hasKey("static"), resolvedVariables, engine));
                    break;
                default:
                    result.append("<").append(tagName);
                    for (Attribute attr : element.attributes()) {
                        if (attr.getKey().startsWith(TAG_PREFIX)) {
                            result.append(" ").append(attr.getKey().replace(TAG_PREFIX, "")).append("=\"").append(eval(attr.getValue(), context, scopeIdentifier, resolvedVariables, engine)).append("\"");
                        } else
                            result.append(" ").append(attr.getKey()).append("=\"").append(attr.getValue()).append("\"");
                    }
                    result.append(">");
                    processChildren(element, context, result, scopeIdentifier, resolvedVariables, engine);
                    result.append("</").append(tagName).append(">");
            }
        }
    }

    private void processChildren(Element element, Context context, StringBuilder result, String scopeIdentifier, Map<String, Object> resolvedVariables, ScriptEngine engine) throws Exception {
        for (Node child : element.childNodes()) {
            processNode(child, context, result, scopeIdentifier, resolvedVariables, engine);
        }
    }

    private void processSwitch(Element element, Context context, StringBuilder result, String scopeIdentifier, Map<String, Object> resolvedVariables, ScriptEngine engine) throws Exception {
        String switchVar = element.attr("var");
        Object switchValue = resolveVariableCached(switchVar, context, scopeIdentifier, resolvedVariables);
        if (switchValue == null) {
            throw new RuntimeException("Switch value cannot be null");
        }

        boolean matched = false;
        List<Element> caseElements = element.getElementsByTag(TAG_PREFIX + "case");
        for (Element caseElement : caseElements) {
            String matchAttr = caseElement.attr("match");
            if (matchAttr.equals(switchValue.toString())) {
                processChildren(caseElement, context, result, scopeIdentifier, resolvedVariables, engine);
                matched = true;
                break;
            }
        }

        if (!matched) {
            Element defaultElement = element.getElementsByTag(TAG_PREFIX + "default").first();
            if (defaultElement != null) {
                processChildren(defaultElement, context, result, scopeIdentifier, resolvedVariables, engine);
            }
        }
    }

    private void processForLoop(Element element, Context context, StringBuilder result, String scopeIdentifier, Map<String, Object> resolvedVariables, ScriptEngine engine) throws Exception {
        String itemName = element.attr("item");
        String listName = element.attr("in");
        Object listObj = resolveVariableCached(listName, context, scopeIdentifier, resolvedVariables);

        if (listObj instanceof List) {
            List<?> items = (List<?>) listObj;
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                Context loopContext = new Context(context);
                loopContext.put(itemName, item);
                engine.put(itemName, item);
                String loopScopeIdentifier = scopeIdentifier + "_" + listName + "_" + i;
                processChildren(element, loopContext, result, loopScopeIdentifier, resolvedVariables, engine);
            }
        }
    }

    private void processRepeat(Element element, Context context, StringBuilder result, String scopeIdentifier, Map<String, Object> resolvedVariables, ScriptEngine engine) throws Exception {
        // Get the number of times to repeat
        Object timesVal = element.attr("times");
        int times;
        try {
            times = Integer.parseInt(((String) timesVal));
        } catch (Exception e) {
            try {
                timesVal = resolveVariableCached(((String) timesVal), context, scopeIdentifier, resolvedVariables);
                times = ((Integer) timesVal);
            } catch (Exception ex) {
                throw new RuntimeException("error parsing variable: " + element.attr("times"), ex);
            }
        }
        // Repeat the content 'times' number of times
        for (int i = 0; i < times; i++) {
            // Pass a unique scope identifier for each iteration
            processChildren(element, context, result, scopeIdentifier + "_" + i, resolvedVariables, engine);
        }
    }


    private String renderComponent(String componentName, Context context, String scopeIdentifier, boolean isStatic, Map<String, Object> resolvedVariables, ScriptEngine engine) throws Exception {
        String result = null;
        String componentFullName = "static:" + context.getLocale().getLanguage() + ":" + componentName + ".component" + componentExtension();
        if (isStatic) {
            result = components.get(componentFullName);
        }
        //
        if (result == null) {
            try {
                String componentTemplate = readComponent(componentName);
                Document componentDoc = Jsoup.parse(componentTemplate);
                StringBuilder componentResult = new StringBuilder();
                for (Node child : componentDoc.body().childNodes()) {
                    processNode(child, context, componentResult, scopeIdentifier + "_" + componentResult, resolvedVariables, engine);
                }
                result = componentResult.toString();
                if (isStatic)
                    components.put(componentFullName, result);
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
