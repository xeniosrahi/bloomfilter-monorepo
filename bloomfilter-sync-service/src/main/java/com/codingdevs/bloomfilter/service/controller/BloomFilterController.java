package com.codingdevs.bloomfilter.service.controller;

import com.codingdevs.bloomfilter.redis.compaction.RedisStreamCompactor;
import com.codingdevs.bloomfilter.service.engine.BloomEcosystemEngine;
import com.codingdevs.bloomfilter.service.engine.CachePenetrationGuardService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class BloomFilterController {

    private final BloomEcosystemEngine bloomEngine;
    private final CachePenetrationGuardService penetrationGuard;
    private final RedisStreamCompactor streamCompactor;

    public BloomFilterController(
            BloomEcosystemEngine bloomEngine,
            CachePenetrationGuardService penetrationGuard,
            RedisStreamCompactor streamCompactor) {
        this.bloomEngine = bloomEngine;
        this.penetrationGuard = penetrationGuard;
        this.streamCompactor = streamCompactor;
    }

    @PostMapping("/bloom/items")
    public ResponseEntity<Map<String, Object>> addItem(@RequestBody AddItemRequest request) {
        boolean newlyAdded = bloomEngine.add(request.element());
        return ResponseEntity.ok(Map.of(
                "element", request.element(),
                "newlyAddedToL2", newlyAdded,
                "status", "SYNCHRONIZED"
        ));
    }

    @PostMapping("/bloom/items/batch")
    public ResponseEntity<Map<String, Object>> addBatch(@RequestBody BatchAddRequest request) {
        int newlyAdded = bloomEngine.addBatch(request.elements());
        return ResponseEntity.ok(Map.of(
                "totalSubmitted", request.elements() == null ? 0 : request.elements().size(),
                "newlyAddedToL2", newlyAdded
        ));
    }

    @GetMapping("/bloom/items/{element}/exists")
    public ResponseEntity<Map<String, Object>> checkExists(@PathVariable("element") String element) {
        boolean mightExist = bloomEngine.mightContain(element);
        return ResponseEntity.ok(Map.of(
                "element", element,
                "mightContain", mightExist
        ));
    }

    /**
     * Cache Penetration Protected Endpoint:
     * Queries L1/L2 Bloom Filter first -> Redis KV Cache -> Primary DB (or records false positive).
     */
    @GetMapping("/users/{id}")
    public ResponseEntity<?> getUserProtected(@PathVariable("id") String id) {
        return penetrationGuard.getProtectedResource(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "error", "RESOURCE_NOT_FOUND",
                        "id", id,
                        "shieldedByBloomFilter", true
                )));
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> registerUser(@RequestBody RegisterUserRequest request) {
        penetrationGuard.registerRecord(request.id(), request.payload());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", request.id(),
                "status", "REGISTERED_AND_INDEXED"
        ));
    }

    /**
     * Zero-Downtime Filter Rotation endpoint (callable manually or via Prometheus Alertmanager webhook).
     */
    @PostMapping("/bloom/admin/rotate")
    public ResponseEntity<Map<String, Object>> rotateFilter(@RequestBody RotateFilterRequest request) {
        int nextCapacity = request.newCapacity() > 0 ? request.newCapacity() : 200_000;
        double nextErrorRate = request.errorRate() > 0.0 ? request.errorRate() : 0.005;
        String activeKey = bloomEngine.rotateActiveFilter(request.newKeyName(), nextCapacity, nextErrorRate);
        return ResponseEntity.ok(Map.of(
                "activeFilterKey", activeKey,
                "newCapacity", nextCapacity,
                "errorRate", nextErrorRate,
                "status", "ROTATED_ZERO_DOWNTIME"
        ));
    }

    @PostMapping("/bloom/admin/compact")
    public ResponseEntity<Map<String, Object>> triggerStreamCompaction() {
        long trimmed = streamCompactor.runCompaction();
        return ResponseEntity.ok(Map.of(
                "trimmedEntries", trimmed,
                "currentStreamLength", streamCompactor.getStreamLength()
        ));
    }

    @GetMapping("/bloom/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(bloomEngine.getClusterStats());
    }

    public record AddItemRequest(String element) {}
    public record BatchAddRequest(List<String> elements) {}
    public record RegisterUserRequest(String id, String payload) {}
    public record RotateFilterRequest(String newKeyName, int newCapacity, double errorRate) {}
}
