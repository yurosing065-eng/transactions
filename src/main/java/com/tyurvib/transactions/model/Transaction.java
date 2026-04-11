package com.tyurvib.transactions.model;

public class Transaction {
    public Type type;
    public String key;
    public double amount;
    public String[] params;
    public long timestamp;
    public boolean rolledBack;
    public double balanceBefore;
    public double balanceAfter;
    public String source; // имя плагина-источника

    public Transaction(Type type, String key, double amount, double balanceBefore, double balanceAfter, String... params) {
        this.type = type;
        this.key = key;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.params = params;
        this.timestamp = System.currentTimeMillis();
        this.source = null;
    }

    public Transaction(Type type, String key, double amount, double balanceBefore, double balanceAfter, String[] params, long timestamp) {
        this.type = type;
        this.key = key;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.params = params;
        this.timestamp = timestamp;
        this.source = null;
    }

    public Transaction withSource(String source) {
        this.source = source;
        return this;
    }
}