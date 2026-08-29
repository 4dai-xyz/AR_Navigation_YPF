# 五道口 wudaokou2 中文搜索词审核报告

## 结论

- 已将 LabelMe `aliases` 写入 App `poi_resolver.items[].aliases`。
- `warning` 表示建议人工复核后再作为稳定 DEMO 搜索词。
- `info` 多为短拼音/简称或同名设施，能用但可能出现多个候选。

## 统计

- issues: `{'info': 54, 'warning': 13}`

## 需重点复核

- `alias_missing_chinese_search_term`: `{"severity": "warning", "code": "alias_missing_chinese_search_term", "poi_id": "poi_b1_dec_27", "name": "dec", "aliases": ["dec", "DEC"]}`
- `alias_missing_chinese_search_term`: `{"severity": "warning", "code": "alias_missing_chinese_search_term", "poi_id": "poi_f1_ieabeauty_71", "name": "ieabeauty", "aliases": ["ieabeauty", "IEA Beauty", "IEABEAUTY", "iea"]}`
- `alias_missing_chinese_search_term`: `{"severity": "warning", "code": "alias_missing_chinese_search_term", "poi_id": "poi_f1_mgg_125", "name": "mgg", "aliases": ["mgg", "MGG"]}`
- `alias_missing_chinese_search_term`: `{"severity": "warning", "code": "alias_missing_chinese_search_term", "poi_id": "poi_f1_only_124", "name": "only", "aliases": ["only", "ONLY", "Only"]}`
- `alias_missing_chinese_search_term`: `{"severity": "warning", "code": "alias_missing_chinese_search_term", "poi_id": "poi_f1_wowbeauty_77", "name": "wowbeauty", "aliases": ["wowbeauty", "Wow Beauty", "WowBeauty"]}`
- `duplicate_normalized_alias`: `{"severity": "warning", "code": "duplicate_normalized_alias", "normalized_alias": "lanxiong", "owners": [{"poi_id": "poi_f1_lanxiong_103", "name": "lanxiong", "floor_id": "F1", "alias": "lanxiong"}, {"poi_id": "poi_f5_lanxiong_103", "name": "lanxiong", "floor_id": "F5", "alias": "lanxiong"}]}`
- `duplicate_normalized_alias`: `{"severity": "warning", "code": "duplicate_normalized_alias", "normalized_alias": "lx", "owners": [{"poi_id": "poi_b1_ruixing_luckin_26", "name": "ruixing(luckin)", "floor_id": "B1", "alias": "lx"}, {"poi_id": "poi_f1_lanxiong_103", "name": "lanxiong", "floor_id": "F1", "alias": "lx"}, {"poi_id": "poi_f1_lianxiang_thinkpad_128", "name": "lianxiang(thinkpad)", "floor_id": "F1", "alias": "lx"}, {"poi_id": "poi_f5_lanxiong_103", "name": "lanxiong", "floor_id": "F5", "alias": "lx"}]}`
- `duplicate_normalized_alias`: `{"severity": "warning", "code": "duplicate_normalized_alias", "normalized_alias": "mc", "owners": [{"poi_id": "poi_f3_mingchuang_14", "name": "mingchuang", "floor_id": "F3", "alias": "mc"}, {"poi_id": "poi_f5_micun_17", "name": "micun", "floor_id": "F5", "alias": "mc"}]}`
- `duplicate_normalized_alias`: `{"severity": "warning", "code": "duplicate_normalized_alias", "normalized_alias": "nike", "owners": [{"poi_id": "poi_f1_nike_67", "name": "nike", "floor_id": "F1", "alias": "nike"}, {"poi_id": "poi_f1_nike_67", "name": "nike", "floor_id": "F1", "alias": "Nike"}, {"poi_id": "poi_f1_nike_67", "name": "nike", "floor_id": "F1", "alias": "NIKE"}, {"poi_id": "poi_f2_nike_67", "name": "nike", "floor_id": "F2", "alias": "nike"}, {"poi_id": "poi_f2_nike_67", "name": "nike", "floor_id": "F2", "alias": "Nike"}, {"poi_id": "poi_f2_nike_67", "name": "nike", "floor_id": "F2", "alias": "NIKE"}]}`
- `duplicate_normalized_alias`: `{"severity": "warning", "code": "duplicate_normalized_alias", "normalized_alias": "uniqlo", "owners": [{"poi_id": "poi_f1_uniqlo_76", "name": "uniqlo", "floor_id": "F1", "alias": "uniqlo"}, {"poi_id": "poi_f1_uniqlo_76", "name": "uniqlo", "floor_id": "F1", "alias": "UNIQLO"}, {"poi_id": "poi_f1_uniqlo_76", "name": "uniqlo", "floor_id": "F1", "alias": "Uniqlo"}, {"poi_id": "poi_f2_uniqlo_15", "name": "uniqlo", "floor_id": "F2", "alias": "uniqlo"}, {"poi_id": "poi_f2_uniqlo_15", "name": "uniqlo", "floor_id": "F2", "alias": "UNIQLO"}, {"poi_id": "poi_f2_uniqlo_15", "name": "uniqlo", "floor_id": "F2", "alias": "Uniqlo"}]}`
- `duplicate_normalized_alias`: `{"severity": "warning", "code": "duplicate_normalized_alias", "normalized_alias": "优衣库", "owners": [{"poi_id": "poi_f1_uniqlo_76", "name": "uniqlo", "floor_id": "F1", "alias": "优衣库"}, {"poi_id": "poi_f2_uniqlo_15", "name": "uniqlo", "floor_id": "F2", "alias": "优衣库"}]}`
- `duplicate_normalized_alias`: `{"severity": "warning", "code": "duplicate_normalized_alias", "normalized_alias": "兰熊", "owners": [{"poi_id": "poi_f1_lanxiong_103", "name": "lanxiong", "floor_id": "F1", "alias": "兰熊"}, {"poi_id": "poi_f5_lanxiong_103", "name": "lanxiong", "floor_id": "F5", "alias": "兰熊"}]}`
- `duplicate_normalized_alias`: `{"severity": "warning", "code": "duplicate_normalized_alias", "normalized_alias": "耐克", "owners": [{"poi_id": "poi_f1_nike_67", "name": "nike", "floor_id": "F1", "alias": "耐克"}, {"poi_id": "poi_f2_nike_67", "name": "nike", "floor_id": "F2", "alias": "耐克"}]}`
