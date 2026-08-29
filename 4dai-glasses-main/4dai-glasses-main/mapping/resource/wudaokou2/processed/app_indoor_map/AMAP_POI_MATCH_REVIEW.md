# 五道口 wudaokou2 店铺高德 POI 对应复核表

## 说明

- 来源：`processed\app_indoor_map\wudaokou_all_floors_poi_resolver.json`。
- 搜索方式：高德网页 JS `place/text` 搜索；每个室内 POI 使用 `mapping_display_name + 五道口购物中心` 等关键词尝试匹配。
- `matched_parent_or_address_and_name` 表示候选结果的高德父 POI 或地址落在五道口购物中心，且名称/alias 命中；仍建议人工抽检。
- `candidate_at_venue_needs_review` 表示候选落在商场内，但名称命中较弱，需要人工确认是否为同一家。
- `candidate_needs_review` / `no_candidate` 不应直接写回 App resolver，需要人工处理。

## 状态统计

- `candidate_at_venue_needs_review`: 34
- `candidate_needs_review`: 4
- `matched_parent_or_address_and_name`: 101

## 表格

| floor | label | display_name | query | status | 高德候选名 | 高德 POI ID | 高德楼层 | 地址 | score | notes |
|---|---|---|---|---|---|---|---|---|---:|---|
| B1 | aichaosuannai | 爱炒酸奶 | 爱炒酸奶 五道口购物中心 | candidate_at_venue_needs_review | 茉酸奶(五道口购物中心店) | B0JDZCCMG2 | B1 | 成府路28号五道口购物中心B1层 | 150 | parent=venue;address_has_venue;address_has_venue_address;floor_match |
| B1 | aihuishou | 爱回收 | 爱回收 五道口购物中心 | matched_parent_or_address_and_name | 爱回收(五道口购物中心店)·手机数码奢侈品黄金回收 | B0H6L7DWTO | - | 成府路28号五道口购物中心B1层 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| B1 | bailuyuan | 白鹿原 | 白鹿原 五道口购物中心 | matched_parent_or_address_and_name | 白鹿原西安肉夹馍·油泼面(五道口购物中心店) | B0L6K1NMAF | - | 成府路28号五道口购物中心B1层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| B1 | baozhu | 包煮 | baozhu 五道口购物中心 | candidate_at_venue_needs_review | VERO MODA(五道口购物中心店) | B0HKLUW5NJ | 2F | 成府路28号五道口购物中心2F层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| B1 | bulilin | 布璃琳 | 布璃琳 五道口购物中心 | candidate_at_venue_needs_review | 布景(五道口购物中心店) | B000AA9XDH | 4F | 成府路28号五道口购物中心4F层L4-02 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| B1 | chabaidao | 茶百道 | 茶百道 五道口购物中心 | matched_parent_or_address_and_name | 茶百道(北京林业大学北路店) | B0JD3C8YXQ | B1 | 成府路28号五道口购物中心B1层 | 200 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;floor_match |
| B1 | chitianshousi | 池田寿司 | 池田寿司 五道口购物中心 | matched_parent_or_address_and_name | 池田寿司·新鲜现做(五道口中心店) | B0L0RU5K6M | B1 | 成府路28号负一层B1-17 | 160 | parent=venue;address_has_venue_address;name_or_alias_match;floor_match |
| B1 | chuanshiduo | 串士多 | 串士多 五道口购物中心 | candidate_at_venue_needs_review | 咏巷炸鸡(五道口购物中心店) | B0KUAUDBZ0 | B1 | 成府路28号五道口购物中心B楼 | 150 | parent=venue;address_has_venue;address_has_venue_address;floor_match |
| B1 | coco | CoCo都可 | coco 五道口购物中心 | matched_parent_or_address_and_name | CoCo都可(五道口购物中心店) | B0FFMGKW5R | B1 | 成府路28号-B1层 | 185 | parent=venue;address_has_venue_address;name_or_alias_match;distance<=100m;floor_match |
| B1 | dajingchazhuang | 大金茶庄 | 大经茶庄 五道口购物中心 | matched_parent_or_address_and_name | 大经茶庄(五道口购物中心店) | B0L2TD3LL0 | - | 成府路28号五道口购物中心B1层 | 205 | parent=venue;address_has_venue;address_has_venue_address;fuzzy_name_match;distance<=100m |
| B1 | dec | dec | dec 五道口购物中心 | candidate_at_venue_needs_review | 五道口购物中心地下停车场 | B0G0T9FPP7 | - | 成府路28号五道口购物中心B2层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| B1 | fanwenhua | 樊文花 | fanwenhua 五道口购物中心 | candidate_at_venue_needs_review | Manner Coffee(五道口购物中心店) | B0H3PR3KMT | 1F | 成府路28号五道口购物中心1F层L1-04 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| B1 | fudi | 福地 | fudi 五道口购物中心 | matched_parent_or_address_and_name | fudi+精选超市(五道口购物中心店) | B0JAFUCEI8 | B1 | 成府路28号五道口购物中心B1层 | 200 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;floor_match |
| B1 | haircamp | 理发店 | 理发店 五道口购物中心 | candidate_at_venue_needs_review | 诗碧曼白发防脱发养发馆(五道口购物中心店) | B0JRTKVLCQ | B1 | 成府路28号五道口购物中心B1层 | 175 | parent=venue;address_has_venue;address_has_venue_address;distance<=100m;floor_match |
| B1 | hongchaoshan | 鸿潮汕 | 鸿潮汕 五道口购物中心 | matched_parent_or_address_and_name | 鸿潮汕广式鸡煲火锅(五道口购物中心店) | B0LGNKSQ0P | - | 北京城区成府路28号五道口购物中心B1层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| B1 | jiajiancheng | 夹拣盛 | 嘉俭生 五道口购物中心 | candidate_at_venue_needs_review | 完美世界影城(五道口购物中心店) | B0J3SU0TTZ | B1 | 成府路28号五道口购物中心B1层 | 150 | parent=venue;address_has_venue;address_has_venue_address;floor_match |
| B1 | jiayibing | 加一冰 | 加一冰 五道口购物中心 | candidate_at_venue_needs_review | 优衣库(五道口购物中心店) | B0LA7UVBER | - | 学院路街道成府路28号五道口购物中心L1-52/53/54 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| B1 | kuafukuafood | 夸父炸串 | 夸父炸串 五道口购物中心 | matched_parent_or_address_and_name | 夸父炸串(五道口购物中心店) | B0JGRP74Y2 | B1 | 北京城区成府路28号-B1层-101-07 | 185 | parent=venue;address_has_venue_address;name_or_alias_match;distance<=100m;floor_match |
| B1 | lenleyuanqihaowu | lenle元气好物 | lenle元气好物 五道口购物中心 | matched_parent_or_address_and_name | lenle元气好物(五道口购物中心店) | B0LKUC0GCN | - | 成府路28号五道口购物中心B1层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| B1 | lianhongqintiantian | 脸红秦甜甜 | 脸红秦甜甜 五道口购物中心 | matched_parent_or_address_and_name | 脸红秦田田(五道口购物中心店) | B0M61CICI0 | - | 北京城区北京城区北京城区成府路28号五道口购物中心B1层 | 180 | parent=venue;address_has_venue;address_has_venue_address;fuzzy_name_match |
| B1 | liumianshou | 刘面手 | 六面寿 五道口购物中心 | matched_parent_or_address_and_name | 六面寿面馆(五道口购物中心店) | B0L1RMAFEM | - | 成府路28号五道口购物中心B1 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| B1 | lutaizong | 卤太宗 | 卤太宗 五道口购物中心 | matched_parent_or_address_and_name | 卤太宗·大鸡腿饭(五道口店) | B0J2L7ZC5A | B1 | 成府路28号五道口购物中心B1层 | 200 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;floor_match |
| B1 | moreyogurt | More酸奶 | 酸奶店 五道口购物中心 | matched_parent_or_address_and_name | 茉酸奶(五道口购物中心店) | B0JDZCCMG2 | B1 | 成府路28号五道口购物中心B1层 | 190 | parent=venue;address_has_venue;address_has_venue_address;fuzzy_name_match;floor_match |
| B1 | qiduyinshi | 七度银饰 | qiduyinshi 五道口购物中心 | candidate_at_venue_needs_review | Manner Coffee(五道口购物中心店) | B0H3PR3KMT | 1F | 成府路28号五道口购物中心1F层L1-04 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| B1 | qishierjiang | 七十二匠 | 七十二匠 五道口购物中心 | matched_parent_or_address_and_name | 柒十二匠(五道口购物中心店) | B0JRXR4KPR | B1 | 成府路28号五道口购物中心B1层 | 190 | parent=venue;address_has_venue;address_has_venue_address;fuzzy_name_match;floor_match |
| B1 | ruixing(luckin) | 瑞幸 | 瑞幸 五道口购物中心 | matched_parent_or_address_and_name | 瑞幸咖啡(五道口购物中心店) | B0J3SU31IF | B1 | 成府路28号五道口购物中心负一层101号 | 225 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m;floor_match |
| B1 | sanyuanmeiyuan | 三元梅园 | 三元梅园 五道口购物中心 | matched_parent_or_address_and_name | 三元梅园(五道口店) | B000A856SF | - | 成府路28号五道口购物中心B1 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| B1 | shaguopo | 砂锅婆 | 砂锅婆 五道口购物中心 | matched_parent_or_address_and_name | 砂锅婆砂锅下饭菜(五道口购物中心店) | B0KDURC9BZ | B1 | 北京城区成府路28号五道口购物中心B1层B1-11 | 200 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;floor_match |
| B1 | shengnuoyi | 圣诺伊 | 圣诺伊 五道口购物中心 | matched_parent_or_address_and_name | 圣诺伊(五道口购物中心店) | B0J3SU31IE | B1 | 成府路28号五道口购物中心B1层B1-12 | 200 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;floor_match |
| B1 | shibiman | 诗碧曼 | 诗碧曼 五道口购物中心 | matched_parent_or_address_and_name | 诗碧曼白发防脱发养发馆(五道口购物中心店) | B0JRTKVLCQ | B1 | 成府路28号五道口购物中心B1层 | 200 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;floor_match |
| B1 | shoudaningmengcha | 手打柠檬茶 | 柠檬茶 五道口购物中心 | matched_parent_or_address_and_name | LINLEE·手打柠檬茶(五道口购物中心店) | B0J2U5P11L | B1 | 北京城区北京城区成府路28号五道口购物中心B1-24 | 225 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m;floor_match |
| B1 | wanmeishijie | 完美世界 | 完美世界 五道口购物中心 | matched_parent_or_address_and_name | 完美世界影城(五道口购物中心店) | B0J3SU0TTZ | B1 | 成府路28号五道口购物中心B1层 | 200 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;floor_match |
| B1 | wuyutai | 吴裕泰 | 吴裕泰 五道口购物中心 | matched_parent_or_address_and_name | 吴裕泰茶庄(五道口购物中心店) | B000A8ZHIE | B1 | 学院路街道成府路28号华联超市地下一层 | 185 | parent=venue;address_has_venue_address;name_or_alias_match;distance<=100m;floor_match |
| B1 | xiechenglvyou | 携程旅游 | 携程旅游 五道口购物中心 | matched_parent_or_address_and_name | 携程旅游五道口门市部 | B0FFJN6ONW | B1 | 成府路28号五道口购物中心地下一层 | 200 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;floor_match |
| B1 | xuejichaohuohaoguozi | 薛记炒货好果子 | 薛记炒货好果子 五道口购物中心 | matched_parent_or_address_and_name | 薛记炒货(五道口购物中心店) | B0K3MUI6GL | - | 北京城区成府路28号五道口购物中心B1层 | 180 | parent=venue;address_has_venue;address_has_venue_address;fuzzy_name_match |
| B1 | yerenxiansheng | 野人先生 | 野人先生 五道口购物中心 | matched_parent_or_address_and_name | 野人先生现做冰淇淋(北京五道口店) | B0LG4CBNA7 | B1 | 北京城区成府路28号五道口购物中心B1-40-1 | 200 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;floor_match |
| B1 | yeyebupaocha | 爷爷不泡茶 | 爷爷不泡茶 五道口购物中心 | matched_parent_or_address_and_name | 爷爷不泡茶NOYEYENOTEA(北京五道口购物中心店) | B0K2VUCV7O | - | 成府路28号邦泰优盛大厦B1层01室18号 | 150 | parent=venue;address_has_venue_address;name_or_alias_match |
| B1 | yimayila | 一麻一辣麻辣香锅 | 一麻一辣麻辣香锅 五道口购物中心 | matched_parent_or_address_and_name | 一麻一辣麻辣香锅(五道口购物中心店) | B0J3SUA2QG | B1 | 北京城区成府路28号五道口购物中心B1层 | 200 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;floor_match |
| B1 | yipinmenguo | 一品焖锅 | yipinmenguo 五道口购物中心 | candidate_at_venue_needs_review | WOWCOLOUR(五道口购物中心店) | B0K0GS4Y5Y | - | 成府路28号五道口购物中心1F层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| B1 | yongxiangzhaji | 永巷炸鸡 | 永巷炸鸡 五道口购物中心 | matched_parent_or_address_and_name | 咏巷炸鸡(五道口购物中心店) | B0KUAUDBZ0 | B1 | 成府路28号五道口购物中心B楼 | 190 | parent=venue;address_has_venue;address_has_venue_address;fuzzy_name_match;floor_match |
| B1 | zhangruhuo | 张如火酸辣粉 | 张如火酸辣粉 五道口购物中心 | matched_parent_or_address_and_name | 张如火酸辣粉(五道口购物中心店) | B0JKJNH87H | B1 | 成府路28号五道口购物中心负一楼 | 200 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;floor_match |
| B1 | zhaozaier | 招仔儿 | 赵崽儿 五道口购物中心 | matched_parent_or_address_and_name | 赵崽儿川式面品(五道口店) | B0JDPGPRHZ | B1 | 成府路28号五道口购物中心B楼 | 200 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;floor_match |
| B1 | zigulu | 紫谷庐 | 紫谷庐 五道口购物中心 | candidate_at_venue_needs_review | 完美世界影城(五道口购物中心店) | B0J3SU0TTZ | B1 | 成府路28号五道口购物中心B1层 | 150 | parent=venue;address_has_venue;address_has_venue_address;floor_match |
| F1 | adidas | 阿迪达斯 | 阿迪达斯 五道口购物中心 | candidate_needs_review | 阿迪达斯(五道口店) | B0LK9HAMMT | - | 成府路27号 | 65 | name_or_alias_match;distance<=300m |
| F1 | bawangchaji | 霸王茶姬 | bawangchaji 五道口购物中心 | candidate_at_venue_needs_review | Manner Coffee(五道口购物中心店) | B0H3PR3KMT | 1F | 成府路28号五道口购物中心1F层L1-04 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F1 | cdgplay | CDG PLAY | CDG PLAY 五道口购物中心 | matched_parent_or_address_and_name | CDG PLAY(五道口购物中心店) | B0J2FU5HP1 | - | 成府路28号五道口购物中心1F层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F1 | chaohongji | 潮宏基 | 潮宏基 五道口购物中心 | matched_parent_or_address_and_name | 潮宏基(五道口购物中心店) | B0J2L7ZJ65 | 1F | 成府路28号五道口购物中心1F层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F1 | dreamroom | 梦想之屋 | dreamroom 五道口购物中心 | candidate_at_venue_needs_review | Manner Coffee(五道口购物中心店) | B0H3PR3KMT | 1F | 成府路28号五道口购物中心1F层L1-04 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F1 | huanyingxingkong | 幻影星空 | 幻影星空 五道口购物中心 | candidate_at_venue_needs_review | 完美世界影城(五道口购物中心店) | B0J3SU0TTZ | B1 | 成府路28号五道口购物中心B1层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F1 | huashuo | 华硕 | 华硕 五道口购物中心 | matched_parent_or_address_and_name | ASUS华硕电脑售后数码配件 | B0H3J75QS6 | 1F | 成府路28号五道口购物中心F1层 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F1 | huawei | 华为 | huawei 五道口购物中心 | matched_parent_or_address_and_name | 华为授权体验店(五道口购物中心) | B0FFJN6OOF | 1F | 五道口购物中心一层 | 195 | parent=venue;address_has_venue;name_or_alias_match;distance<=100m |
| F1 | ieabeauty | ieabeauty | IEA Beauty 五道口购物中心 | candidate_at_venue_needs_review | BEAUTY STARSKY(五道口购物中心店) | B0KANU1QOO | - | 成府路28号五道口购物中心1F层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F1 | jack | 杰克琼斯 | 杰克琼斯 五道口购物中心 | matched_parent_or_address_and_name | JACK&JONES(五道口购物中心店) | B0J1SUYQLV | 1F | 成府路28号五道口购物中心1F层 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F1 | jiroumayi | 肌肉蚂蚁 | 肌肉蚂蚁 五道口购物中心 | candidate_at_venue_needs_review | 茉酸奶(五道口购物中心店) | B0JDZCCMG2 | B1 | 成府路28号五道口购物中心B1层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F1 | lano | 兰诺 | 兰诺 五道口购物中心 | matched_parent_or_address_and_name | 兰诺(五道口购物中心店) | B0FFKS7ZEU | 1F | 成府路28号华联商厦一层(五道口地铁站B南口步行150米) | 175 | parent=venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F1 | lanxiong | 兰熊 | 兰熊 五道口购物中心 | matched_parent_or_address_and_name | 兰熊鲜奶(五道口购物中心店) | B0HG45TEND | 1F | 成府路28号五道口购物中心5F层501 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F1 | laofengxiang | 老凤祥 | 老凤祥 五道口购物中心 | candidate_needs_review | 老凤祥(领展购物中心店) | B0K675DQAQ | F1 | 丹棱街甲1号北京中关村领展广场F1层 | 60 | name_or_alias_match;floor_match |
| F1 | lianxiang(thinkpad) | 联想 | 联想 五道口购物中心 | matched_parent_or_address_and_name | 联想百应电脑维修销售(智商务五道口店) | B0KR57W25D | 1F | 成府路28号五道口购物中心1层101室14号 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F1 | maidanglao | 麦当劳 | 麦当劳 五道口购物中心 | matched_parent_or_address_and_name | 麦当劳(五道口购物中心店) | B0I2HLAIJ2 | 1F | 成府路28号五道口购物中心1F层102-46室 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F1 | manner coffee | Manner咖啡 | Manner咖啡 五道口购物中心 | matched_parent_or_address_and_name | Manner Coffee(五道口购物中心店) | B0H3PR3KMT | 1F | 成府路28号五道口购物中心1F层L1-04 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F1 | mgg | mgg | mgg 五道口购物中心 | matched_parent_or_address_and_name | MGG(五道口购物中心店) | B0JR9GCS6J | 1F | 成府路28号五道口购物中心1F层L1-06 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F1 | nike | 耐克 | 耐克 五道口购物中心 | matched_parent_or_address_and_name | 耐克Nike(五道口购物中心店) | B0HBR643ZT | 2F | 中关村东路8号卜蜂超市1层(五道口地铁站B南口步行290米) | 155 | parent=venue;name_or_alias_match;distance<=100m |
| F1 | noisyteddy | 嘈杂小熊 | noisyteddy 五道口购物中心 | matched_parent_or_address_and_name | NOISY TEDDY(五道口购物中心店) | B0KRB5VYPD | - | 成府路28号五道口购物中心1F层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F1 | only | only | only 五道口购物中心 | candidate_needs_review | Only Flowers只和花(中关村店) | B0KB5MB468 | F1 | 丹棱街甲1号北京中关村领展广场F1层 | 60 | name_or_alias_match;floor_match |
| F1 | popmart | 泡泡玛特 | 泡泡玛特 五道口购物中心 | matched_parent_or_address_and_name | POP MART泡泡玛特(五道口购物中心店) | B0HD7HOM8L | 1F | 成府路28号邦泰优盛大厦1层(霸王茶姬对面) | 150 | parent=venue;address_has_venue_address;name_or_alias_match |
| F1 | starbuck | 星巴克 | 星巴克 五道口购物中心 | matched_parent_or_address_and_name | 星巴克(五道口购物中心店) | B0FFJN6OP3 | 2F | 北京城区北京城区海淀街道府路28号五道口商城1层 | 155 | parent=venue;name_or_alias_match;distance<=100m |
| F1 | uniqlo | 优衣库 | 优衣库 五道口购物中心 | matched_parent_or_address_and_name | 优衣库(五道口购物中心店) | B0LA7UVBER | - | 学院路街道成府路28号五道口购物中心L1-52/53/54 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F1 | wdkcoffee | WDK咖啡 | WDK咖啡 五道口购物中心 | matched_parent_or_address_and_name | WDK Coffee(五道口购物中心店) | B0LD7101YL | - | 北京城区成府路28号五道口购物中心1层101室 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F1 | wowbeauty | wowbeauty | Wow Beauty 五道口购物中心 | matched_parent_or_address_and_name | WOW BEAUTY(五道口购物中心店) | B0M62RWN04 | - | 成府路28号五道口购物中心1F层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F1 | xiaomi | 小米 | 小米 五道口购物中心 | matched_parent_or_address_and_name | 小米之家(海淀区五道口购物中心专卖店) | B0KRU11D6J | 1F | 学院路街道成府路28号五道口购物中心南区一层扶梯对面 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F1 | yuanmaishanqiu | 原麦山丘 | 原麦山丘 五道口购物中心 | matched_parent_or_address_and_name | 原麦山丘(五道口购物中心店) | B0FFFWAWR8 | 1F | 北京城区五道口成府路28号(五道口地铁站B南口步行300米) | 175 | parent=venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F1 | zhongguohuangjin | 中国黄金 | 中国黄金 五道口购物中心 | matched_parent_or_address_and_name | 中国黄金(五道口购物中心店) | B0FFJN6OOI | 1F | 成府路28号五道口购物中心1F层L1-26A | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F1 | zhoudafu | 周大福 | 周大福 五道口购物中心 | matched_parent_or_address_and_name | 周大福(五道口购物中心店) | B0FFGYN54U | 1F | 成府路28号1层(五道口地铁站B南口步行310米) | 150 | parent=venue;address_has_venue_address;name_or_alias_match |
| F1 | zhouliufu | 周六福 | zhouliufu 五道口购物中心 | candidate_at_venue_needs_review | Manner Coffee(五道口购物中心店) | B0H3PR3KMT | 1F | 成府路28号五道口购物中心1F层L1-04 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F2 | guya | 谷娅 | 谷娅 五道口购物中心 | matched_parent_or_address_and_name | 谷娅(五道口购物中心店) | B0H3YC8L6D | 2F | 成府路28号五道口购物中心二层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F2 | jieshengji | 杰胜基 | 杰胜基 五道口购物中心 | candidate_at_venue_needs_review | 潮宏基(五道口购物中心店) | B0J2L7ZJ65 | 1F | 成府路28号五道口购物中心1F层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F2 | kisscat | 接吻猫 | 接吻猫 五道口购物中心 | matched_parent_or_address_and_name | KISSCAT(BHG华联百货店) | B0G2FSZQJR | 2F | 成府路28号五道口购物中心2F层 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F2 | lily | 丽丽 | 丽丽 五道口购物中心 | matched_parent_or_address_and_name | LILY商务时装(北京五道口购物中心店) | B0IAJBOQO7 | 2F | 成府路28号五道口购物中心2F层L2-17 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F2 | linqingxuan | 林清轩 | 林清轩 五道口购物中心 | matched_parent_or_address_and_name | 林清轩(五道口购物中心店) | B0FFF3D7VN | 2F | 成府路28号五道口购物中心2F层L2-14 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F2 | lovetococo | 爱可可 | 爱可可 五道口购物中心 | candidate_at_venue_needs_review | 紫涵(五道口购物中心店) | B0H1SCK9WX | 2F | 成府路28号五道口购物中心2F层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F2 | mofan | 摩梵 | mofan 五道口购物中心 | matched_parent_or_address_and_name | 摩凡(五道口购物中心店) | B0K1OPFFAJ | 2F | 成府路28号五道口购物中心二楼 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F2 | nike | 耐克 | 耐克 五道口购物中心 | matched_parent_or_address_and_name | 耐克Nike(五道口购物中心店) | B0HBR643ZT | 2F | 中关村东路8号卜蜂超市1层(五道口地铁站B南口步行290米) | 155 | parent=venue;name_or_alias_match;distance<=100m |
| F2 | quchenshi | 屈臣氏 | quchenshi 五道口购物中心 | matched_parent_or_address_and_name | 屈臣氏(五道口购物中心店) | B0FFF6XKYE | 2F | 成府路28号五道口购物中心2F层L2-07 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F2 | taipingniao | 太平鸟 | 太平鸟 五道口购物中心 | matched_parent_or_address_and_name | 太平鸟男装(五道口购物中心店) | B0L11ULE13 | - | 成府路28号五道口购物中心3F层 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F2 | tata | TATA 鞋店 | TATA 鞋店 五道口购物中心 | matched_parent_or_address_and_name | TATA(五道口购物中心店) | B0FFJ9UN69 | 2F | 城府路28号五道口购物中心F2 | 170 | parent=venue;address_has_venue;name_or_alias_match |
| F2 | uniqlo | 优衣库 | 优衣库 五道口购物中心 | matched_parent_or_address_and_name | 优衣库(五道口购物中心店) | B0LA7UVBER | - | 学院路街道成府路28号五道口购物中心L1-52/53/54 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F2 | velwin | VELWIN | VELWIN 五道口购物中心 | candidate_at_venue_needs_review | RENKOMAY(五道口购物中心店) | B0K3UNT5G5 | 2F | 成府路28号五道口购物中心2F层L2-10 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F2 | wagas | 沃歌斯 | 沃歌斯 五道口购物中心 | matched_parent_or_address_and_name | Wagas沃歌斯(北京五道口购物中心店) | B0FFKZYLDL | - | 北京城区成府路28号五道口购物中心1F层 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F2 | zhiwuyisheng | 植物医生 | zhiwuyisheng 五道口购物中心 | candidate_at_venue_needs_review | Manner Coffee(五道口购物中心店) | B0H3PR3KMT | 1F | 成府路28号五道口购物中心1F层L1-04 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F2 | zihan | 子涵 | 紫涵 五道口购物中心 | matched_parent_or_address_and_name | 紫涵(五道口购物中心店) | B0H1SCK9WX | 2F | 成府路28号五道口购物中心2F层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F3 | 6ixty | 6IXTY 8IGTY(五道口购物中心店) | 6ixty 五道口购物中心 | matched_parent_or_address_and_name | 6IXTY 8IGTY(五道口购物中心店) | B000A8WWEQ | 3F | 成府路28号五道口购物中心3F层L3-08 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F3 | jiabeile | 嘉贝乐 | 佳贝乐 五道口购物中心 | candidate_at_venue_needs_review | 西贝(五道口购物中心店) | B0FFGZQ7LZ | 5F | 成府路28号五道口购物中心F5层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F3 | jiajixiaozhan | 嘉吉小站 | jiajixiaozhan 五道口购物中心 | candidate_at_venue_needs_review | Manner Coffee(五道口购物中心店) | B0H3PR3KMT | 1F | 成府路28号五道口购物中心1F层L1-04 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F3 | kama | 卡玛 | 卡玛 五道口购物中心 | candidate_needs_review | KAMA CLASSICS(西单购物中心北京店) | B0FFK1XZD3 | - | 西长安街街道西单北大街132号西单购物中心2楼(近西单地铁站) | 50 | name_or_alias_match |
| F3 | kule | 酷乐 | 酷乐 五道口购物中心 | matched_parent_or_address_and_name | 酷乐潮玩(五道口购物中心店) | B0FFMD23EF | 3F | 成府路28号五道口购物中心3F层L3-18 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F3 | mingchuang | 名创优品 | mingchuang 五道口购物中心 | matched_parent_or_address_and_name | 名创优品(五道口购物中心店) | B0FFGYN55K | 3F | 成府路中段五道口购物中心三层L3-09 | 195 | parent=venue;address_has_venue;name_or_alias_match;distance<=100m |
| F3 | refeng | 热风 | 热风 五道口购物中心 | matched_parent_or_address_and_name | Hotwind热风(五道口购物中心店) | B0FFGYN55R | 3F | 成府路28号五道口购物中心3F层L3-07 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F3 | shuimujintang | 水木锦堂 | 水木锦堂 五道口购物中心 | matched_parent_or_address_and_name | 水木锦堂自助铁板烧(五道口店) | B0FFIEBXQ2 | 3F | 北京城区成府路28号3层301-19(五道口华联3层电梯旁) | 150 | parent=venue;address_has_venue_address;name_or_alias_match |
| F3 | songshanmiandian | 松山棉店 | 松山棉店 五道口购物中心 | matched_parent_or_address_and_name | 松山棉店(五道口购物中心店) | B0L1FSW4ND | - | 五道口购物中心3层(五道口地铁站B南口步行230米) | 170 | parent=venue;address_has_venue;name_or_alias_match |
| F3 | thegreenparty | 绿色派对 | thegreenparty 五道口购物中心 | matched_parent_or_address_and_name | The Green Party(五道口购物中心店) | B0FFGY826U | 3F | 成府路28号五道口购物中心3F层L3-17 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F3 | wanshenshuyuan | 万圣书园 | 万圣书园 五道口购物中心 | matched_parent_or_address_and_name | 万圣书园(五道口购物中心店) | B0J3SU7JEZ | 4F | 成府路28号五道口购物中心3F层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F3 | zhucha | 煮茶 | 煮茶 五道口购物中心 | candidate_at_venue_needs_review | 煮叶(五道口购物中心店) | B0LGDUDZP2 | - | 成府路28号五道口购物中心3层301号 | 165 | parent=venue;address_has_venue;address_has_venue_address;distance<=100m |
| F4 | aizhijianbojiemeijia | 爱指间博洁美甲 | 爱指间博洁美甲 五道口购物中心 | matched_parent_or_address_and_name | 爱指间·柏睫美甲美睫(五道口购物中心店) | B0K37UOZNT | 4F | 北京城区成府路28号五道口购物中心四层 | 180 | parent=venue;address_has_venue;address_has_venue_address;fuzzy_name_match |
| F4 | bujing | 布景 | 布景 五道口购物中心 | matched_parent_or_address_and_name | 布景(五道口购物中心店) | B000AA9XDH | 4F | 成府路28号五道口购物中心4F层L4-02 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F4 | chaojixingxing | 超级猩猩 | 超级猩猩 五道口购物中心 | matched_parent_or_address_and_name | 超级猩猩(五道口购物中心店) | B0I63HY5I5 | 4F | 成府路28号五道口购物中心4层L4-33/34 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F4 | chenjinshijie | 沉浸世界 | 沉浸世界 五道口购物中心 | matched_parent_or_address_and_name | 沉浸世界(五道口购物中心店) | B0J2RBDDUP | 4F | 成府路28号五道口购物中心4F层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F4 | haimatizhaoxiangguan | 海马体照相馆 | 海马体照相馆 五道口购物中心 | matched_parent_or_address_and_name | 海马体照相馆(五道口购物中心店) | B0HU5OPGVH | 4F | 城府路28号五道口购物中心4层L4-29商铺 | 170 | parent=venue;address_has_venue;name_or_alias_match |
| F4 | huluwa | 葫芦娃 | 葫芦娃 五道口购物中心 | matched_parent_or_address_and_name | 葫芦娃一家人火锅(五道口购物中心店) | B0GK2XNEUM | 4F | 成府路28号五道口购物中心4层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F4 | kuxiu | 酷秀 KTV | 酷秀 KTV 五道口购物中心 | matched_parent_or_address_and_name | 酷秀KTV·派对(五道口店) | B0L3M1P7IK | 4F | 北京城区成府路28号五道口购物中心4层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F4 | xianyuxian | 鲜芋仙 | 鲜芋仙 五道口购物中心 | matched_parent_or_address_and_name | 鲜芋仙(五道口店) | B000A8W852 | 4F | 北京城区成府路28号五道口购物中心4F层L4-09 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F4 | youchang | 优创 | youchang 五道口购物中心 | candidate_at_venue_needs_review | Manner Coffee(五道口购物中心店) | B0H3PR3KMT | 1F | 成府路28号五道口购物中心1F层L1-04 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F4 | zhijianbalei | 指尖芭蕾 | 指尖芭蕾 五道口购物中心 | matched_parent_or_address_and_name | 指间芭蕾(五道口购物中心店) | B0FFIBW1PB | 4F | 成府路28号五道口购物中心4F层 | 180 | parent=venue;address_has_venue;address_has_venue_address;fuzzy_name_match |
| F5 | baozhunailao | 宝珠奶酪 | baozhunailao 五道口购物中心 | candidate_at_venue_needs_review | Manner Coffee(五道口购物中心店) | B0H3PR3KMT | 1F | 成府路28号五道口购物中心1F层L1-04 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F5 | chaozhouren | 潮粥人 | 潮粥人 五道口购物中心 | matched_parent_or_address_and_name | 潮粥人·潮汕菜(五道口购物中心店) | B0JGPCG00Y | 5F | 成府路28号五道口购物中心5F层L5-13 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F5 | hefulaomian | 和府捞面 | 和府捞面 五道口购物中心 | matched_parent_or_address_and_name | 和府捞面(北京五道口购物中心店) | B0G1BK8M2A | 5F | 成府路28号五道口购物中心5F层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F5 | huguowu | 糊锅屋 | 糊锅屋 五道口购物中心 | candidate_at_venue_needs_review | 台巷里·台湾菜(五道口购物中心店) | B0IU9SUCH0 | 5F | 成府路28号五道口购物中心5层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F5 | huolu | 火炉 | 火炉 五道口购物中心 | candidate_at_venue_needs_review | 台巷里·台湾菜(五道口购物中心店) | B0IU9SUCH0 | 5F | 成府路28号五道口购物中心5层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F5 | jiangbiancheng | 江边城外 | 江边城外 五道口购物中心 | matched_parent_or_address_and_name | 江边城外烤全鱼(五道口购物中心店) | B0FFJN6OQR | 5F | 成府路28号五道口购物中心5F层L5-02 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F5 | jvbaoyuan | 聚宝源 | 聚宝源 五道口购物中心 | matched_parent_or_address_and_name | 聚宝源(五道口店) | B0J3P5999I | - | 北京城区成府路28号五道口购物中心5F层 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F5 | lanxiong | 兰熊 | 兰熊 五道口购物中心 | matched_parent_or_address_and_name | 兰熊鲜奶(五道口购物中心店) | B0HG45TEND | 1F | 成府路28号五道口购物中心5F层501 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F5 | laoxiangji | 老乡鸡 | 老乡鸡 五道口购物中心 | matched_parent_or_address_and_name | 老乡鸡(北京五道口店) | B0HBR64J8R | 5F | 北京城区成府路28号五层501室18号 | 175 | parent=venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F5 | miaoamei | 苗阿妹 | 苗阿妹 五道口购物中心 | matched_parent_or_address_and_name | 苗阿妹贵州羊肉粉(五道口购物中心店) | B0L2F71W31 | - | 北京城区成府路28号5层501室 | 150 | parent=venue;address_has_venue_address;name_or_alias_match |
| F5 | micun | 米村 | 米村 五道口购物中心 | matched_parent_or_address_and_name | 米村拌饭(五道口购物中心店) | B0J3SUV923 | 5F | 成府路28号五道口购物中心五层L5-03号商铺 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F5 | taixiangli | 台巷里 | 台巷里 五道口购物中心 | matched_parent_or_address_and_name | 台巷里·台湾菜(五道口购物中心店) | B0IU9SUCH0 | 5F | 成府路28号五道口购物中心5层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F5 | xiangshanting | 香山亭 | xiangshanting 五道口购物中心 | candidate_at_venue_needs_review | CDG PLAY(五道口购物中心店) | B0J2FU5HP1 | - | 成府路28号五道口购物中心1F层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F5 | xiantan | 鲜潭 | 鲜潭 五道口购物中心 | matched_parent_or_address_and_name | 鲜潭蒸汽石锅鱼(五道口购物中心店) | B0FFM3HP5E | 5F | 北京城区成府路28号五道口购物中心五层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F5 | xianyouji | 鲜有基 | 鲜有基 五道口购物中心 | matched_parent_or_address_and_name | 鲜有基参鸡汤(五道口购物中心店) | B0I3ASRRWH | 5F | 成府路28号五道口购物中心5F层L5-37 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F5 | xibei | 西贝 | 西贝 五道口购物中心 | matched_parent_or_address_and_name | 西贝(五道口购物中心店) | B0FFGZQ7LZ | 5F | 成府路28号五道口购物中心F5层 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F5 | xinzhaxinpai | 新炸新派 | 新炸新派 五道口购物中心 | candidate_at_venue_needs_review | 茉酸奶(五道口购物中心店) | B0JDZCCMG2 | B1 | 成府路28号五道口购物中心B1层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F5 | xishupaofu | 西树泡芙 | 西树泡芙 五道口购物中心 | matched_parent_or_address_and_name | 西树泡芙(五道口购物中心店) | B0JDPPPC3N | 5F | 五道口购物中心5层501室36号 | 170 | parent=venue;address_has_venue;name_or_alias_match |
| F5 | xunyecai | 旬野菜 | 旬野菜 五道口购物中心 | matched_parent_or_address_and_name | 旬野菜日式和牛寿喜烧专门店(五道口购物中心店) | B0I235YUJO | 5F | 成府路28号五道口购物中心5层17号 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F5 | zhengxian | 争鲜 | 争鲜 五道口购物中心 | matched_parent_or_address_and_name | 争鲜回转寿司(海淀五道口PLUS店) | B0FFGZAV74 | 5F | 北京城区成府路28号五道口购物中心5层 | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F5 | zuimian | 醉面 | 醉面 五道口购物中心 | matched_parent_or_address_and_name | 醉面(五道口购物中心店) | B0H6G6TXA9 | 5F | 北京城区成府路28号(五道口购物中心五层) | 190 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match |
| F6 | daoxiao | 刀小 | daoxiao 五道口购物中心 | candidate_at_venue_needs_review | CDG PLAY(五道口购物中心店) | B0J2FU5HP1 | - | 成府路28号五道口购物中心1F层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F6 | jvqi | 局气 | 局气 五道口购物中心 | matched_parent_or_address_and_name | 局气(五道口店) | B0FFGJOM6C | 6F | 北四环中路五道口购物中心D座六层L6-05B | 195 | parent=venue;address_has_venue;name_or_alias_match;distance<=100m |
| F6 | longrenjv | 龙人居 | longrenjv 五道口购物中心 | candidate_at_venue_needs_review | Manner Coffee(五道口购物中心店) | B0H3PR3KMT | 1F | 成府路28号五道口购物中心1F层L1-04 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F6 | quanmao | 全茂 | 全茂 五道口购物中心 | candidate_at_venue_needs_review | 五道口购物中心地下停车场 | B0G0T9FPP7 | - | 成府路28号五道口购物中心B2层 | 140 | parent=venue;address_has_venue;address_has_venue_address |
| F6 | yunhaiyao | 云海肴 | yunhaiyao 五道口购物中心 | matched_parent_or_address_and_name | 云海肴云南菜(五道口购物中心店) | B0FFJN6OR0 | - | 成府路28号五道口购物中心A座6层 | 215 | parent=venue;address_has_venue;address_has_venue_address;name_or_alias_match;distance<=100m |
| F6 | zhandianpisa | 站点披萨 | 站点披萨 五道口购物中心 | matched_parent_or_address_and_name | Tubestation站点比萨(五道口购物中心店) | B0FFJN6OQZ | 6F | 成府路28号五道口购物中心4层 | 180 | parent=venue;address_has_venue;address_has_venue_address;fuzzy_name_match |
