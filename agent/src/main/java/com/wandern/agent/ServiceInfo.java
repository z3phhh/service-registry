package com.wandern.agent;

import lombok.Data;

@Data
public class ServiceInfo {
    private String name;
    private String address;
    private int port;
    private String healthEndpoint;
}