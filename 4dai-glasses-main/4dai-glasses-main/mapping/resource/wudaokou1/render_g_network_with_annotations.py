import json
from collections import Counter
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT_DIR = Path(__file__).resolve().parent
ANNOTATION_DIR = ROOT_DIR / "annotation_points"
G_NETWORK_DIR = (
    ROOT_DIR
    / "processed"
    / "walkable_network_previews"
    / "results"
    / "algorithm_g_cell_portal_bridge_pruned"
)
OUTPUT_DIR = (
    ROOT_DIR
    / "processed"
    / "annotation_overlays"
    / "algorithm_g_cell_portal_bridge_pruned_clean_indexed"
)

NETWORK_IMAGE_SUFFIX = "_algorithm_g_cell_portal_bridge_pruned_preview.jpg"
OUTPUT_IMAGE_SUFFIX = "_algorithm_g_with_annotation_index_preview.jpg"
SIDE_PANEL_WIDTH = 560

CONNECTOR_LABEL_PREFIXES = (
    "escalator_",
    "elevator",
    "step",
    "stairs",
    "wc",
)
ENTRANCE_LABELS = {"EG", "NG", "WG", "SG", "WEST_GATE", "EAST_GATE", "NORTH_GATE", "SOUTH_GATE"}

COLORS = {
    "poi": (255, 214, 64),
    "entrance": (0, 200, 83),
    "escalator_up": (255, 112, 67),
    "escalator_down": (239, 83, 80),
    "elevator": (171, 71, 188),
    "step": (141, 110, 99),
    "wc": (41, 121, 255),
    "unknown_connector": (255, 167, 38),
}


def load_font(size: int) -> ImageFont.ImageFont:
    candidates = [
        Path(r"C:\Windows\Fonts\msyh.ttc"),
        Path(r"C:\Windows\Fonts\simhei.ttf"),
        Path(r"C:\Windows\Fonts\arial.ttf"),
    ]
    for font_path in candidates:
        if font_path.exists():
            return ImageFont.truetype(str(font_path), size=size)
    return ImageFont.load_default()


def classify_label(label: str) -> str:
    normalized = label.strip()
    lower = normalized.lower()
    if normalized.upper() in ENTRANCE_LABELS:
        return "entrance"
    if lower.startswith("escalator_up"):
        return "escalator_up"
    if lower.startswith("escalator_down"):
        return "escalator_down"
    if lower == "elevator":
        return "elevator"
    if lower == "step" or lower == "stairs":
        return "step"
    if lower == "wc":
        return "wc"
    if lower.startswith(CONNECTOR_LABEL_PREFIXES):
        return "unknown_connector"
    return "poi"


def is_connector(category: str) -> bool:
    return category not in {"poi", "entrance"}


def text_size(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.ImageFont) -> tuple[int, int]:
    box = draw.textbbox((0, 0), text, font=font)
    return box[2] - box[0], box[3] - box[1]


def shorten(text: str, limit: int) -> str:
    return text if len(text) <= limit else f"{text[: limit - 1]}…"


def draw_index_marker(
    draw: ImageDraw.ImageDraw,
    point: tuple[float, float],
    number: int,
    color: tuple[int, int, int],
    font: ImageFont.ImageFont,
) -> None:
    x, y = point
    marker_radius = 10
    outline_radius = marker_radius + 3
    draw.ellipse(
        (x - outline_radius, y - outline_radius, x + outline_radius, y + outline_radius),
        fill=(255, 255, 255),
        outline=(20, 20, 20),
        width=2,
    )
    draw.ellipse((x - marker_radius, y - marker_radius, x + marker_radius, y + marker_radius), fill=color)
    text = str(number)
    text_width, text_height = text_size(draw, text, font)
    draw.text((x - text_width / 2, y - text_height / 2 - 1), text, fill=(10, 10, 10), font=font)


def draw_side_panel(
    draw: ImageDraw.ImageDraw,
    panel_x: int,
    image_height: int,
    floor_id: str,
    counts: Counter,
    rendered: list[dict],
) -> None:
    title_font = load_font(24)
    font = load_font(17)
    small_font = load_font(15)
    draw.rectangle((panel_x, 0, panel_x + SIDE_PANEL_WIDTH, image_height), fill=(250, 250, 250))
    draw.line((panel_x, 0, panel_x, image_height), fill=(60, 60, 60), width=2)

    x = panel_x + 18
    y = 20
    draw.text((x, y), f"{floor_id.upper()} 标注索引", fill=(20, 20, 20), font=title_font)
    y += 38
    draw.text((x, y), "地图上只显示编号点，避免遮挡 G 路网。", fill=(60, 60, 60), font=small_font)
    y += 28
    draw.text(
        (x, y),
        f"POI {counts['poi']} | entrance {counts['entrance']} | connector {sum(v for k, v in counts.items() if k not in {'poi', 'entrance'})}",
        fill=(20, 20, 20),
        font=font,
    )
    y += 34

    legend_items = [
        ("poi", "店铺/POI"),
        ("entrance", "入口"),
        ("escalator_up", "上行扶梯"),
        ("escalator_down", "下行扶梯"),
        ("elevator", "电梯"),
        ("step", "楼梯"),
        ("wc", "WC"),
    ]
    for index, (category, label) in enumerate(legend_items):
        item_x = x + (index % 2) * 250
        item_y = y + (index // 2) * 24
        draw.ellipse((item_x, item_y + 4, item_x + 13, item_y + 17), fill=COLORS[category], outline=(30, 30, 30))
        draw.text((item_x + 20, item_y), label, fill=(40, 40, 40), font=small_font)
    y += 106

    header = "#   type              label / group_id"
    draw.text((x, y), header, fill=(80, 80, 80), font=small_font)
    y += 24
    row_height = 26
    max_rows = max(1, (image_height - y - 20) // row_height)
    for row_index, item in enumerate(rendered[:max_rows]):
        row_y = y + row_index * row_height
        if row_index % 2 == 0:
            draw.rectangle((panel_x + 8, row_y - 2, panel_x + SIDE_PANEL_WIDTH - 8, row_y + row_height - 2), fill=(242, 242, 242))
        color = COLORS[item["category"]]
        draw.ellipse((x, row_y + 5, x + 14, row_y + 19), fill=color, outline=(30, 30, 30))
        draw.text((x + 22, row_y + 1), f"{item['index']:02d}", fill=(20, 20, 20), font=font)
        category_text = item["category"].replace("escalator_", "esc_")
        draw.text((x + 66, row_y + 2), shorten(category_text, 13), fill=(70, 70, 70), font=small_font)
        group_text = f" #{item['group_id']}" if item.get("group_id") is not None else ""
        draw.text((x + 190, row_y + 1), shorten(f"{item['label']}{group_text}", 35), fill=(20, 20, 20), font=font)
    if len(rendered) > max_rows:
        draw.text((x, image_height - 30), f"还有 {len(rendered) - max_rows} 个点未显示在索引表。", fill=(180, 60, 60), font=small_font)


def render_floor(annotation_path: Path) -> dict:
    floor_key = annotation_path.stem.lower()
    network_path = G_NETWORK_DIR / f"{floor_key}{NETWORK_IMAGE_SUFFIX}"
    if not network_path.exists():
        return {
            "floor_id": floor_key.upper(),
            "annotation_json": str(annotation_path),
            "network_preview": str(network_path),
            "status": "skipped",
            "skipped_reason": "missing_algorithm_g_network_preview",
        }

    data = json.loads(annotation_path.read_text(encoding="utf-8"))
    shapes = data.get("shapes", [])
    base_image = Image.open(network_path).convert("RGB")
    image = Image.new("RGB", (base_image.width + SIDE_PANEL_WIDTH, base_image.height), (250, 250, 250))
    image.paste(base_image, (0, 0))
    draw = ImageDraw.Draw(image)
    marker_font = load_font(13)

    counts: Counter = Counter()
    connector_count = 0
    poi_count = 0
    rendered = []
    for shape in shapes:
        if shape.get("shape_type") != "point" or not shape.get("points"):
            continue
        label = str(shape.get("label", "")).strip()
        if not label:
            continue
        x, y = shape["points"][0]
        category = classify_label(label)
        counts[category] += 1
        if category == "poi":
            poi_count += 1
        if is_connector(category):
            connector_count += 1

        group_id = shape.get("group_id")
        point_index = len(rendered) + 1
        draw_index_marker(draw, (float(x), float(y)), point_index, COLORS[category], marker_font)
        rendered.append(
            {
                "index": point_index,
                "label": label,
                "group_id": group_id,
                "category": category,
                "x": round(float(x), 3),
                "y": round(float(y), 3),
            }
        )

    draw_side_panel(draw, base_image.width, image.height, floor_key, counts, rendered)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    output_path = OUTPUT_DIR / f"{floor_key}{OUTPUT_IMAGE_SUFFIX}"
    image.save(output_path, quality=92)
    return {
        "floor_id": floor_key.upper(),
        "annotation_json": str(annotation_path),
        "network_preview": str(network_path),
        "output": str(output_path),
        "status": "generated",
        "total_shapes": len(shapes),
        "rendered_points": len(rendered),
        "poi_count": poi_count,
        "connector_count": connector_count,
        "category_counts": dict(sorted(counts.items())),
        "render_style": "clean_indexed_side_panel",
    }


def render_contact_sheet(generated_items: list[dict]) -> str | None:
    image_paths = [Path(item["output"]) for item in generated_items if item.get("status") == "generated"]
    if not image_paths:
        return None

    thumbnails = []
    for path in image_paths:
        image = Image.open(path).convert("RGB")
        image.thumbnail((360, 800))
        canvas = Image.new("RGB", (380, 850), (245, 245, 245))
        canvas.paste(image, ((380 - image.width) // 2, 10))
        draw = ImageDraw.Draw(canvas)
        font = load_font(18)
        floor_name = path.name.split("_", 1)[0].upper()
        draw.text((14, 812), floor_name, fill=(20, 20, 20), font=font)
        thumbnails.append(canvas)

    columns = 4
    rows = (len(thumbnails) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 380, rows * 850), (235, 235, 235))
    for index, thumb in enumerate(thumbnails):
        x = (index % columns) * 380
        y = (index // columns) * 850
        sheet.paste(thumb, (x, y))

    output_path = OUTPUT_DIR / "all_floors_algorithm_g_with_annotation_index_contact_sheet.jpg"
    sheet.save(output_path, quality=92)
    return str(output_path)


def main() -> None:
    annotation_paths = sorted(ANNOTATION_DIR.glob("*.json"))
    items = [render_floor(path) for path in annotation_paths]
    generated_items = [item for item in items if item.get("status") == "generated"]
    contact_sheet = render_contact_sheet(generated_items)

    summary = {
        "algorithm": "algorithm_g_cell_portal_bridge_pruned",
        "render_style": "clean_indexed_side_panel",
        "source_annotation_dir": str(ANNOTATION_DIR),
        "source_network_dir": str(G_NETWORK_DIR),
        "output_dir": str(OUTPUT_DIR),
        "contact_sheet": contact_sheet,
        "items": items,
    }
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    summary_path = OUTPUT_DIR / "summary.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"generated={len(generated_items)} skipped={len(items) - len(generated_items)}")
    print(f"output_dir={OUTPUT_DIR}")
    if contact_sheet:
        print(f"contact_sheet={contact_sheet}")
    print(f"summary={summary_path}")


if __name__ == "__main__":
    main()
