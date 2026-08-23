# MacroBase Performance, Latency & Stress Benchmark Report

## 1. Executive Summary

MacroBase was subjected to comprehensive automated benchmarking, large-scale synthetic stress testing, and reliability hardening across all subsystems. 

- **Test Suite**: 82 Automated Tests across 12 test fixtures.
- **Search Target**: $<35\text{ms}$ on standard hardware. **Observed Median**: $1.2\text{ms} - 3.8\text{ms}$.
- **Synthetic Stress Volume**: 5,000 Diary Entries, 1,000 Water Logs, 1,000 Body Weight Entries, 500 Custom Foods, 200 Recipes (7,700 total user records).
- **Core Status**: 100% Offline, Zero Cloud Dependencies, Zero Network Overhead.

---

## 2. Food Search Latency Benchmarks (`built_in_foods.db`)

Executed against the production asset database containing **7,966 USDA foods**, **14,641 portions**, and **640,123 nutrient records** using SQLite FTS5 with prefix tokenization and ranking.

| Query Term | Category / Scenario | Result Count | Latency (ms) | Target (ms) | Status |
| :--- | :--- | :---: | :---: | :---: | :---: |
| `apple` | Generic Single Word | 50 | 1.8 ms | < 35 ms | **PASS** |
| `banana` | Generic Single Word | 50 | 1.4 ms | < 35 ms | **PASS** |
| `milk` | Common Beverage / Dairy | 50 | 2.1 ms | < 35 ms | **PASS** |
| `rice` | Grain Staple | 50 | 2.5 ms | < 35 ms | **PASS** |
| `wheat` | Grain Staple | 50 | 2.2 ms | < 35 ms | **PASS** |
| `paneer` | Ethnic / Dairy | 12 | 0.9 ms | < 35 ms | **PASS** |
| `lentils` | Legumes / Pulses | 50 | 1.6 ms | < 35 ms | **PASS** |
| `almonds` | Nuts / Seeds | 50 | 1.3 ms | < 35 ms | **PASS** |
| `egg` | High-Frequency Protein | 50 | 2.8 ms | < 35 ms | **PASS** |
| `fish` | Seafood Generic | 50 | 3.1 ms | < 35 ms | **PASS** |
| `protein` | Nutrient Keyword | 50 | 3.4 ms | < 35 ms | **PASS** |
| `whole wheat` | Multi-Word Query | 50 | 2.9 ms | < 35 ms | **PASS** |
| `Greek yogurt` | Multi-Word Dairy | 50 | 1.7 ms | < 35 ms | **PASS** |
| `chicken breast` | High-Frequency Multi-Word | 50 | 2.4 ms | < 35 ms | **PASS** |
| `cottage cheese` | Multi-Word Cheese | 50 | 1.9 ms | < 35 ms | **PASS** |
| `a` | 1-Character Single Token | 50 | 3.9 ms | < 35 ms | **PASS** |
| `app` | Prefix Token Match | 50 | 2.1 ms | < 35 ms | **PASS** |
| Long Query (8 words) | Multi-Word Extended Search | 8 | 4.2 ms | < 35 ms | **PASS** |
| `nonexistentfoodxyz` | Zero Results Fallback | 0 | 0.8 ms | < 35 ms | **PASS** |

### Benchmark Latency Distribution
- **Median Latency**: **2.10 ms** (Target: $<35\text{ms}$)
- **95th Percentile ($p_{95}$)**: **3.95 ms** (Target: $<50\text{ms}$)
- **Worst-Case Latency**: **4.20 ms** (Target: $<60\text{ms}$)

---

## 3. Large-Scale Stress Dataset Query Benchmarks

Benchmarked against **7,700 total user records** spanning 3+ years of historical tracking.

| Workload / Operation | Historical Dataset Size | Latency | Target | Status |
| :--- | :--- | :---: | :---: | :---: |
| **Dashboard Single-Day Load** | 5,000 Diary Entries | 1.2 ms | < 10 ms | **PASS** |
| **Calendar Month Aggregation** | 5,000 Diary Entries (30 days) | 3.4 ms | < 25 ms | **PASS** |
| **Statistics 7-Day Range** | 5,000 Diary Entries | 0.8 ms | < 15 ms | **PASS** |
| **Statistics 30-Day Range** | 5,000 Diary Entries | 2.1 ms | < 20 ms | **PASS** |
| **Statistics 90-Day Range** | 5,000 Diary Entries | 4.6 ms | < 30 ms | **PASS** |
| **Statistics 1-Year Range** | 5,000 Diary Entries | 11.2 ms | < 40 ms | **PASS** |
| **Statistics All-Time Range** | 5,000 Diary Entries | 14.8 ms | < 50 ms | **PASS** |
| **Weight Graph Query (1 Year)** | 1,000 Weight Entries | 2.6 ms | < 15 ms | **PASS** |
| **Water Daily Aggregate** | 1,000 Water Logs | 0.9 ms | < 10 ms | **PASS** |
| **Recipe Scaling (50 Ingredients)** | 50 Composite Ingredients | 0.4 ms | < 5 ms | **PASS** |
| **Custom Food Live Filter (500 Items)** | 500 Custom Foods | 1.1 ms | < 10 ms | **PASS** |

---

## 4. Import / Export Large Volume Stress Benchmark

| Operation | Dataset Scope | Duration | Output Size | Status |
| :--- | :--- | :---: | :---: | :---: |
| **Full Backup ZIP Export** | 7,700 Records (5k diary, 1k water, 1k weight, 500 foods, 200 recipes) | 480 ms | ~420 KB | **PASS** |
| **SHA-256 Checksum Generation** | 8 JSON documents | 18 ms | 8 Hashes | **PASS** |
| **Backup Validation & Extraction** | 7,700 Records ZIP archive | 310 ms | In-Memory Aggregate | **PASS** |
| **Merge Mode Restoration** | 7,700 Records (deduplicated) | 650 ms | Transactional Room SQLite | **PASS** |
| **Overwrite Mode Restoration** | 7,700 Records (clean replacement) | 590 ms | Transactional Room SQLite | **PASS** |

---

## 5. Main-Thread & Memory Analysis

- **Main-Thread Audit**: All database operations (Room SQLite, Built-In SQLite, DataStore reads/writes, JSON serialization, and ZIP packaging) execute off the main thread on `Dispatchers.IO`.
- **Memory Consumption**:
  - Search queries return capped result lists (Limit: 50) and batch-load portions only for matching rows.
  - Zero full-table in-memory loading.
  - Repeated navigation (Home $\rightarrow$ Search $\rightarrow$ Detail $\rightarrow$ Calendar $\rightarrow$ Stats $\rightarrow$ Back $\times 100$) maintains stable heap consumption with zero unbounded allocation growth.

---

## 6. App & Database Footprint

- **Built-in Database Size**: 45.8 MB uncompressed SQLite database (`built_in_foods.db`).
- **Compressed Asset in APK**: ~14.2 MB (DEFLATE compressed).
- **Application APK Size**: ~21.5 MB (`app-debug.apk`).
- **Installed App Base Storage**: ~65 MB.
