package com.agent.coding.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "env_vars")
public class EnvVarEntity {
    @Id
    @Column(name = "env_key", length = 255)
    private String key;

    @Column(name = "env_value", length = 4096, nullable = false)
    private String value = "";

    public EnvVarEntity() {}
    public EnvVarEntity(String key, String value) { this.key = key; this.value = value; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
