package com.doctool.model;

public class UserPreference {
    private Long id;
    private String username;
    private String prefKey;
    private String prefValue;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPrefKey() { return prefKey; }
    public void setPrefKey(String prefKey) { this.prefKey = prefKey; }
    public String getPrefValue() { return prefValue; }
    public void setPrefValue(String prefValue) { this.prefValue = prefValue; }
}
