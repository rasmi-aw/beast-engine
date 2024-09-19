package com.beastwall.beastengine;

import java.util.Map;

public class BeastTextEngine extends BeastEngine {


    public BeastTextEngine() {
        super();
    }

    public BeastTextEngine(String componentsPath) {
        super(componentsPath);
    }

    @Override
    public String process(String template, Map<String, Object> context) throws Exception {
        //
        context.keySet().parallelStream().forEach(k -> {
            engine.get().put(k, context.get(k));
        });
        System.out.println(engine.get().get("user"));
        //
        StringBuilder builder = new StringBuilder();
        processText(template, context, builder, null);
        String output = builder.toString();
        clearCache();
        return output;
    }

    @Override
    public String processComponent(String componentName, Map<String, Object> context) throws Exception {
        return process(readComponent(componentName), context);
    }

    @Override
    String componentExtension() {
        return ".txt";
    }


}
