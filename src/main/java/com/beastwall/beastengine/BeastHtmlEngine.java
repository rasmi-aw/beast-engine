package com.beastwall.beastengine;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import javax.script.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;

public class BeastHtmlEngine extends BeastEngine {

    private static final Parser PARSER = Parser.htmlParser();

    public BeastHtmlEngine() {
        super();
    }

    public BeastHtmlEngine(String componentsPath) {
        super(componentsPath);
    }

    @Override
    public String process(String template, Context context) throws Exception {
        Document doc = Jsoup.parse(template, "", PARSER);
        ScriptEngine engine = scriptEngineThreadLocal.get();
        Map<String, Object> resolvedVariables = new HashMap<>(context.size() * 2);

        // Pre-bind context variables
        context.forEach((key, value) -> {
            engine.put(key, value);
            resolvedVariables.put(key, value);
        });

        processNode(doc, context, "", resolvedVariables, engine);
        String result = doc.toString().replaceAll("bs:", "");
        clearCache();
        return result;
    }

    private void processNode(Node node, Context context, String scopeIdentifier,
                             Map<String, Object> resolvedVariables, ScriptEngine engine) throws Exception {
        if (node instanceof TextNode) {
            processTextNode((TextNode) node, context, scopeIdentifier, resolvedVariables, engine);
        } else if (node instanceof Element) {
            Element element = (Element) node;
            String tagName = element.tagName();

            switch (tagName) {
                case TAG_PREFIX + "var":
                    processVar(element, context, engine);
                    element.remove();
                    break;
                case TAG_PREFIX + "if":
                    processIf(element, context, scopeIdentifier, resolvedVariables, engine);
                    break;
                case TAG_PREFIX + "switch":
                    processSwitch(element, context, scopeIdentifier, resolvedVariables, engine);
                    break;
                case TAG_PREFIX + "for":
                    processFor(element, context, scopeIdentifier, resolvedVariables, engine);
                    break;
                case TAG_PREFIX + "repeat":
                    processRepeat(element, context, scopeIdentifier, resolvedVariables, engine);
                    break;
                case TAG_PREFIX + "component":
                    processComponent(element, context, scopeIdentifier, resolvedVariables, engine);
                    break;

                case TAG_PREFIX + "router":
                    String route = ((String) context.get(TAG_PREFIX + "path")).trim();
                    element.childNodes().stream().filter(child -> child.nameIs("route") && route.equalsIgnoreCase(child.attr("path").trim())).findFirst().ifPresent(child -> {
                        //
                        try {
                            element.replaceWith(renderComponent(child.attr("component").trim(), context, scopeIdentifier, child.attributes().hasKey("static"), resolvedVariables, engine));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                    break;
                default:
                    processAttributes(element, context, scopeIdentifier, resolvedVariables, engine);
                    processChildren(element, context, scopeIdentifier, resolvedVariables, engine);
            }
        }
    }

    // Optimize text node processing
    private void processTextNode(TextNode textNode, Context context, String scopeIdentifier,
                                 Map<String, Object> resolvedVariables, ScriptEngine engine) throws ScriptException {
        String text = textNode.text();
        if (!text.contains("{{")) {
            return; // Early exit for non-template text
        }

        StringBuilder sb = stringBuilderPool.get();
        sb.setLength(0); // Reset StringBuilder

        Matcher matcher = INTERPOLATION_PATTERN.matcher(text);
        int lastPos = 0;

        while (matcher.find()) {
            // Append text before match
            sb.append(text, lastPos, matcher.start());

            String expression = matcher.group(1).trim();
            Object result;

            // Fast path for simple variables
            if (SIMPLE_VARIABLE_PATTERN.matcher(expression).matches()) {
                result = resolveVariableFast(expression, context, scopeIdentifier, resolvedVariables);
            } else {
                // Use global cache for compiled scripts
                CompiledScript compiled = expressionCacheThreadLocal.get().computeIfAbsent(expression,
                        exp -> {
                            try {
                                return ((Compilable) engine).compile(exp);
                            } catch (ScriptException e) {
                                throw new RuntimeException("Failed to compile: " + exp, e);
                            }
                        });
                result = compiled.eval();
            }

            if (result != null) {
                sb.append(result);
            }

            lastPos = matcher.end();
        }

        // Append remaining text
        if (lastPos < text.length()) {
            sb.append(text, lastPos, text.length());
        }

        textNode.text(sb.toString());
    }
    private void processVar(Element element, Context context, ScriptEngine engine) throws ScriptException {
        String[] expressions = element.ownText().split(";");
        for (String expression : expressions) {
            String[] parts = expression.split("=", 2);
            if (parts.length == 2) {
                String varName = parts[0].trim();
                String varValue = parts[1].trim();
                Object value = engine.eval(varValue);
                context.put(varName, value);
                engine.put(varName, value);
            }
        }
    }

    private void processIf(Element element, Context context, String scopeIdentifier,
                           Map<String, Object> resolvedVariables, ScriptEngine engine) throws Exception {
        String condition = element.attr("condition");
        if (evaluateCondition(condition, context, scopeIdentifier, resolvedVariables, engine)) {
            processChildren(element, context, scopeIdentifier, resolvedVariables, engine);
            Elements children = element.children();
            children.forEach(element::before);
        }
        element.remove();
    }

    private void processSwitch(Element element, Context context, String scopeIdentifier,
                               Map<String, Object> resolvedVariables, ScriptEngine engine) throws Exception {
        String switchVar = element.attr("var");
        Object switchValue = resolveVariableFast(switchVar, context, scopeIdentifier, resolvedVariables);

        Element matchingCase = element.getElementsByTag(TAG_PREFIX + "case").stream()
                .filter(caseE -> Objects.equals(
                        resolveVariableFast(caseE.attr("match"), context, scopeIdentifier, resolvedVariables),
                        switchValue))
                .findFirst()
                .orElse(element.getElementsByTag(TAG_PREFIX + "default").first());

        if (matchingCase != null) {
            processChildren(matchingCase, context, scopeIdentifier, resolvedVariables, engine);
            matchingCase.childNodes().forEach(element::before);
        }

        element.remove();
    }

    private void processFor(Element element, Context context, String scopeIdentifier,
                            Map<String, Object> resolvedVariables, ScriptEngine engine) throws Exception {
        String itemName = element.attr("item");
        String listName = element.attr("in");

        // First try to resolve the collection directly from context
        Object collectionObj = context.get(listName);

        // If not found directly, try resolving through the variable resolver
        if (collectionObj == null) {
            collectionObj = resolveVariableFast(listName, context, scopeIdentifier, resolvedVariables);
            // System.out.println("Resolved collection through resolver: " + collectionObj);
        }

        // Validate collection
        if (collectionObj == null) {
            throw new RuntimeException("Collection '" + listName + "' not found in context");
        }

        Collection<?> collection;
        if (collectionObj instanceof Collection) {
            collection = (Collection<?>) collectionObj;
        } else if (collectionObj.getClass().isArray()) {
            collection = Arrays.asList((Object[]) collectionObj);
        } else {
            throw new RuntimeException("Value for '" + listName + "' is not a collection or array");
        }

        // Store the original value of the item variable if it exists
        Object originalValue = context.get(itemName);

        try {
            int index = 0;
            for (Object item : collection) {
                // Update both context and engine with the current item
                context.put(itemName, item);
                engine.put(itemName, item);

                // Add additional loop variables
                String indexVar = itemName + "_index";
                context.put(indexVar, index);
                engine.put(indexVar, index);

                // Create a unique scope identifier for this iteration
                String loopScopeIdentifier = scopeIdentifier + "_" + listName + "_" + index;

                // Clear any cached values for this item in resolvedVariables
                resolvedVariables.remove(loopScopeIdentifier + ":" + itemName);

                // Clone and process the element
                Element clone = element.clone();
                processChildren(clone, context, loopScopeIdentifier, resolvedVariables, engine);
                clone.childNodes().forEach(element::before);

                index++;
            }
        } finally {
            // Restore the original value or remove the temporary variable
            if (originalValue != null) {
                context.put(itemName, originalValue);
                engine.put(itemName, originalValue);
            } else {
                context.remove(itemName);
                engine.put(itemName, null);
            }
        }

        element.remove();
    }

    private void processRepeat(Element element, Context context, String scopeIdentifier,
                               Map<String, Object> resolvedVariables, ScriptEngine engine) throws Exception {
        String timesAttr = element.attr("times");
        int times;

        try {
            times = Integer.parseInt(timesAttr);
        } catch (NumberFormatException e) {
            Object resolvedTimes = resolveVariableFast(timesAttr, context, scopeIdentifier, resolvedVariables);
            if (resolvedTimes instanceof Number) {
                times = ((Number) resolvedTimes).intValue();
            } else {
                throw new RuntimeException("Invalid 'times' attribute for bs:repeat: " + timesAttr);
            }
        }

        for (int i = 0; i < times; i++) {
            Element clone = element.clone();
            processChildren(clone, context, scopeIdentifier + "_" + i, resolvedVariables, engine);
            clone.childNodes().forEach(element::before);
        }

        element.remove();
    }

    private void processComponent(Element element, Context context, String scopeIdentifier,
                                  Map<String, Object> resolvedVariables, ScriptEngine engine) throws Exception {
        String componentName = element.attr("name");
        boolean isStatic = element.hasAttr("static");

        Element componentContent = renderComponent(componentName, context, scopeIdentifier, isStatic, resolvedVariables, engine);
        element.empty();
        element.appendChildren(componentContent.childNodes());
        element.unwrap();
    }

    private void processAttributes(Element element, Context context, String scopeIdentifier,
                                   Map<String, Object> resolvedVariables, ScriptEngine engine) throws ScriptException {
        for (Attribute attr : element.attributes()) {
            String attrKey = attr.getKey();
            String attrValue = attr.getValue();

            try {
                if (attrKey.startsWith(TAG_PREFIX)) {
                    String newAttrKey = attrKey.substring(TAG_PREFIX.length());
                    Object evaluatedValue = eval(attrValue, context, scopeIdentifier, resolvedVariables, engine);
                    element.removeAttr(attrKey);
                    element.attr(newAttrKey, evaluatedValue != null ? evaluatedValue.toString() : "");
                } else if (attrValue.contains("{{")) {
                    Object evaluatedValue = eval(attrValue, context, scopeIdentifier, resolvedVariables, engine);
                    element.attr(attrKey, evaluatedValue != null ? evaluatedValue.toString() : "");
                }
            } catch (Exception e) {
                //System.out.println("error here in eval");
            }
        }
    }

    private void processChildren(Element element, Context context, String scopeIdentifier,
                                 Map<String, Object> resolvedVariables, ScriptEngine engine) throws Exception {
        for (Node child : element.childNodes()) {
            processNode(child, context, scopeIdentifier, resolvedVariables, engine);
        }
    }

    private Element renderComponent(String componentName, Context context, String scopeIdentifier,
                                    boolean isStatic, Map<String, Object> resolvedVariables,
                                    ScriptEngine engine) throws Exception {
        String componentFullName = "static:" + context.getLocale().getLanguage() + ":"
                + componentName + ".component" + componentExtension();
        Element result = isStatic ? (Element) components.get(componentFullName) : null;

        if (result == null) {
            result = ((Element) readComponent(componentName)).clone();
            processNode(result, context, scopeIdentifier + "_" + componentName, resolvedVariables, engine);
            if (isStatic) {
                components.put(componentFullName, result);
            }
        }
        return result;
    }

    private CompiledScript getCompiledScript(String expression, ScriptEngine engine) throws ScriptException {
        return expressionCacheThreadLocal.get().computeIfAbsent(expression, exp -> {
            try {
                return ((Compilable) engine).compile(exp);
            } catch (ScriptException e) {
                throw new RuntimeException("Failed to compile expression: " + exp, e);
            }
        });
    }

    private Object resolveVariableFast(String expression, Context context, String scopeIdentifier,
                                       Map<String, Object> resolvedVariables) {
        String cacheKey = scopeIdentifier + ":" + expression;
        return resolvedVariables.computeIfAbsent(cacheKey, k -> {
            try {
                String[] parts = expression.split("\\.");
                Object value = context.get(parts[0]);

                for (int i = 1; i < parts.length && value != null; i++) {
                    value = getPropertyValue(value, parts[i]);
                }
                return value;
            } catch (Exception e) {
                return null;
            }
        });
    }

    private Object getPropertyValue(Object obj, String property) throws Exception {
        String cacheKey = obj.getClass().getName() + ":" + property;
        Method method = methodCacheThreadLocal.get().get(cacheKey);

        if (method == null) {
            Class<?> cls = obj.getClass();
            String getter = "get" + Character.toUpperCase(property.charAt(0)) + property.substring(1);

            try {
                method = cls.getMethod(getter);
            } catch (NoSuchMethodException e) {
                if (property.startsWith("is")) {
                    method = cls.getMethod(property);
                } else {
                    getter = "is" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
                    method = cls.getMethod(getter);
                }
            }

            methodCacheThreadLocal.get().put(cacheKey, method);
        }

        return method.invoke(obj);
    }

    @Override
    public String processComponent(String componentName, Context context) throws Exception {
        ScriptEngine engine = scriptEngineThreadLocal.get();
        context.forEach(engine::put);
        String result = renderComponent(componentName, context, "", false, new HashMap<>(), engine).outerHtml();
        clearCache();
        return result;
    }

    @Override
    public String componentExtension() {
        return ".html";
    }
}