package com.beastwall.beastengine;

public class BeastCssEngine extends BeastTextEngine {

    public BeastCssEngine() {
        super();
    }

    public BeastCssEngine(String componentsPath) {
        super(componentsPath);
    }
    
    @Override
    String componentExtension() {
        return ".css";
    }
}
