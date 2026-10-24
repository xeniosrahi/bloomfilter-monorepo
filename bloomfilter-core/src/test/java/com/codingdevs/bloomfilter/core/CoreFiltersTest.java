package com.codingdevs.bloomfilter.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class CoreFiltersTest {

    @Test
    void testSizingCalculatorFormulas() {
        long bits = FilterSizingCalculator.optimalNumBitsLong(10_000_000L, 0.01);
        assertEquals(95_850_584L, bits, 5L);
        double mb = FilterSizingCalculator.estimateMemoryMegabytes(bits);
        assertEquals(11.42, mb, 0.05);

        int fpBits = FilterSizingCalculator.optimalCuckooFingerprintBits(4, 0.01);
        assertEquals(10, fpBits);
    }

    @Test
    void testStandardBloomFilterAccuracy() {
        StandardBloomFilter<String> filter = new StandardBloomFilter<>(10_000, 0.01);
        filter.add("hello");
        filter.add("world");

        assertTrue(filter.mightContain("hello"));
        assertTrue(filter.mightContain("world"));
        assertFalse(filter.mightContain("absent_key_xyz"));
        assertTrue(filter.getSaturationRatio() > 0.0);
    }

    @Test
    void testGuavaBloomFilterAdapter() throws IOException {
        GuavaBloomFilterAdapter adapter = new GuavaBloomFilterAdapter(5_000, 0.01);
        assertTrue(adapter.put("user123"));
        assertTrue(adapter.mightContain("user123"));
        assertFalse(adapter.mightContain("user789"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        adapter.writeTo(out);
        GuavaBloomFilterAdapter restored = GuavaBloomFilterAdapter.readFrom(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(restored.mightContain("user123"));
        assertFalse(restored.mightContain("user789"));
    }

    @Test
    void testCountingBloomFilterAddAndDelete() {
        CountingBloomFilter<String> cbf = new CountingBloomFilter<>(1_000, 0.01);
        cbf.add("session_token");
        assertTrue(cbf.mightContain("session_token"));

        assertTrue(cbf.delete("session_token"));
        assertFalse(cbf.mightContain("session_token"));
    }

    @Test
    void testPartialKeyCuckooFilterInsertAndDelete() {
        PartialKeyCuckooFilter cuckoo = new PartialKeyCuckooFilter(1_024);
        assertTrue(cuckoo.insert("user_99"));
        assertTrue(cuckoo.contains("user_99"));

        assertTrue(cuckoo.delete("user_99"));
        assertFalse(cuckoo.contains("user_99"));
    }

    @Test
    void testScalableBloomFilterDynamicLayersAndBinarySerialization() throws IOException {
        ScalableBloomFilter scalable = new ScalableBloomFilter(1_000, 0.01);
        for (int i = 0; i < 5_000; i++) {
            scalable.add("user_id_" + i);
        }

        assertTrue(scalable.getLayerCount() > 1, "Expected dynamic layers > 1 after exceeding 80% threshold");
        assertTrue(scalable.mightContain("user_id_0"));
        assertTrue(scalable.mightContain("user_id_2500"));
        assertTrue(scalable.mightContain("user_id_4999"));
        assertFalse(scalable.mightContain("non_existent_user_id"));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        scalable.serialize(bos);

        ScalableBloomFilter restored = ScalableBloomFilter.deserialize(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals(scalable.getLayerCount(), restored.getLayerCount());
        assertEquals(scalable.getTotalInsertions(), restored.getTotalInsertions());
        assertTrue(restored.mightContain("user_id_2500"));
        assertTrue(restored.mightContain("user_id_4999"));
        assertFalse(restored.mightContain("non_existent_user_id"));
    }
}
