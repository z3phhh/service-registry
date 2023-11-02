package com.wandern.agent.node;

import com.wandern.agent.AgentService;
import com.wandern.agent.data.LivenessDataStore;
import com.wandern.agent.data.ServiceMetricsDataStore;
import com.wandern.clients.MetricsDTO;
import com.wandern.clients.NodeMetricsDTO;
import com.wandern.model.ServiceMetrics;
import com.wandern.starter.ServiceInfoCollector;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.*;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class NodeService {

    private static final Logger logger = LoggerFactory.getLogger(NodeService.class);

    private final LivenessDataStore livenessDataStore;
    private final ServiceMetricsDataStore serviceMetricsMap;
    private final KafkaTemplate<String, NodeMetricsDTO> kafkaTemplate;

    private final String nodeId;

    private NodeMetricsDTO latestMetrics;

    @Autowired
    public NodeService(LivenessDataStore livenessDataStore,
                       ServiceMetricsDataStore serviceMetricsMap,
                       KafkaTemplate<String, NodeMetricsDTO> kafkaTemplate) {
        this.livenessDataStore = livenessDataStore;
        this.serviceMetricsMap = serviceMetricsMap;
        this.kafkaTemplate = kafkaTemplate;
        this.nodeId = generateNodeId();
    }

    @Scheduled(fixedRate = 60000)
    public void updateAndSendMetrics() throws SocketException {
        latestMetrics = calculateMetricsNode();
        sendMetricsToKafka(latestMetrics);
    }

    public NodeMetricsDTO calculateMetricsNode() throws SocketException {
        // Calculating service counts
        long totalServices = livenessDataStore.getAllLivenessStatuses().size();
        long activeServices = livenessDataStore.getAllLivenessStatuses().values().stream()
                .filter(status -> "UP".equals(status.getStatus()))
                .count();
        long inactiveServices = totalServices - activeServices;

        // Collecting aggregated metrics
        MetricsDTO aggregatedMetrics = calculateAggregateMetrics();
        var nodeIp = ServiceInfoCollector.getExternalIpAddress();
        return new NodeMetricsDTO(
                nodeId,
                nodeIp,
                totalServices,
                activeServices,
                inactiveServices,
                aggregatedMetrics
        );
    }

    private MetricsDTO calculateAggregateMetrics() {
        // Get all metrics and aggregate them
        Collection<ServiceMetrics> allMetrics = serviceMetricsMap.getAllMetrics().values();
        double avgSystemLoad = allMetrics
                .stream()
                .mapToDouble(ServiceMetrics::systemLoad)
                .average()
                .orElse(0);

        double avgJvmCpuLoad = allMetrics
                .stream()
                .mapToDouble(ServiceMetrics::jvmCpuLoad)
                .average()
                .orElse(0);

        long totalUsedMemoryMB = allMetrics.stream()
                .mapToLong(ServiceMetrics::usedMemoryMB)
                .sum();

        long totalFreeMemoryMB = allMetrics
                .stream()
                .mapToLong(ServiceMetrics::freeMemoryMB)
                .sum();

        int totalThreads = allMetrics
                .stream()
                .mapToInt(ServiceMetrics::totalThreads)
                .sum();

        return new MetricsDTO(
                avgSystemLoad,
                avgJvmCpuLoad,
                totalUsedMemoryMB,
                totalFreeMemoryMB,
                totalThreads
        );
    }

    public NodeMetricsDTO getNodeMetrics() {
        return latestMetrics; // Возвращает последние рассчитанные метрики
    }

    private String generateNodeId() {
        var random = new Random();
        return "node-" + String.format("%04d", random.nextInt(10000));
    }

    //change to internal ip
    private String resolveNodeIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }


    private void sendMetricsToKafka(NodeMetricsDTO nodeMetricsDTO) {
        kafkaTemplate.send("node-metrics-topic", nodeMetricsDTO);
    }
}