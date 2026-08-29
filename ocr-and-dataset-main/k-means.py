import numpy as np

def post_process_ocr_results(detected_objects, distance_threshold=150):
    """
    OCR 后处理核心：空间聚类 -> 坐标排序 -> 语义拼接
    :param detected_objects: 传入的原始识别列表，格式为: [(x, y, w, h, 'class_name', conf), ...]
    :param distance_threshold: 同一家店铺招牌字与字之间的最大像素距离阈值
    :return: 拼接好的招牌文字和识别出的独立Logo列表
    """
    if not detected_objects:
        return []

    # ----------- 第一步：空间聚类 (贪心邻近算法，替代高能耗的K-Means) -----------
    # 目的：把同一个招牌上的字归为一组（同一个Cluster），分开不同店铺的字
    clusters = []
    
    for obj in detected_objects:
        x, y, w, h, cls_name, conf = obj
        center_x = x + w / 2
        center_y = y + h / 2
        
        placed = False
        for cluster in clusters:
            # 计算当前物体与该聚类中最后一个物体的中心点距离
            last_obj = cluster[-1]
            lx, ly, lw, lh, _, _ = last_obj
            last_center_x = lx + lw / 2
            last_center_y = ly + lh / 2
            
            distance = np.sqrt((center_x - last_center_x)**2 + (center_y - last_center_y)**2)
            
            # 如果距离小于阈值，说明它们大概率属于同一个门头招牌
            if distance < distance_threshold:
                cluster.append(obj)
                placed = True
                break
                
        if not placed:
            clusters.append([obj])

    # ----------- 第二步：聚类内部排序与语义解析 -----------
    final_results = []
    
    for idx, cluster in enumerate(clusters):
        # 按照 X 坐标（从左到右）对这块招牌里的字进行精细排序！解决谁前谁后的问题
        cluster_sorted = sorted(cluster, key=lambda item: item[0])
        
        chinese_chars = [] # 存放普通汉字序列
        graphic_logos = [] # 存放独立的图形Logo
        
        for obj in cluster_sorted:
            _, _, _, _, cls_name, conf = obj
            
            # 💡 核心魔法：利用你提议的 logo_ 前缀进行分流！
            if cls_name.startswith("logo_"):
                # 提取出干净的品牌名，比如 logo_starbuck -> starbuck
                clean_brand_name = cls_name.replace("logo_", "")
                graphic_logos.append((clean_brand_name, conf))
            else:
                # 如果是普通中文字符，直接加进拼字队列
                chinese_chars.append(cls_name)
        
        # 拼接汉字字符串 (比如 ['he', 'fu', 'lao', 'mian'] -> "hefu laomian")
        # 真实项目中你可以在这里挂一个拼音转汉字的字典，直接输出"和府捞面"
        shop_text = "".join(chinese_chars) 
        
        final_results.append({
            "cluster_id": idx + 1,
            "signboard_text": shop_text if shop_text else None,
            "standalone_logos": graphic_logos if graphic_logos else None
        })
        
    return final_results

# ================= 模拟运行测试 =================
if __name__ == "__main__":
    # 模拟 R-CNN 识别出来的一堆乱序的框（包括坐标、类别和置信度）
    # 故意把“和府捞面”的顺序打乱，并混入一个星巴克的图形Logo
    mock_detected_output = [
        (110, 50, 40, 40, "lao", 0.98),       # ‘捞’ (位置靠后)
        (70, 53, 41, 40, "fu", 0.99),         # ‘府’ (位置靠前)
        (30, 50, 38, 42, "he", 0.95),         # ‘和’ (最左边)
        (150, 48, 39, 40, "mian", 0.97),      # ‘面’ (最右边)
        (600, 120, 80, 80, "logo_starbuck", 0.99) # 远处另一个独立店面的星巴克Logo图形
    ]
    
    parsed_output = post_process_ocr_results(mock_detected_output)
    
    import json
    print(json.dumps(parsed_output, indent=4, ensure_ascii=False))