import os
from PIL import Image, ImageDraw

def render_icon(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    pad = int(size * 0.02)
    r = int(size * 0.28)
    box = [pad, pad, size - pad, size - pad]
    
    draw.rounded_rectangle(box, radius=r, fill=(15, 23, 110))
    draw.rounded_rectangle([pad+1, pad+1, size-pad-1, size-pad-1], radius=r-1, outline=(80, 130, 250, 180), width=max(1, int(size * 0.012)))

    p_left = int(size * 0.22)
    p_top = int(size * 0.36)
    p_right = int(size * 0.78)
    p_bottom = int(size * 0.78)
    p_r = int(size * 0.12)
    
    draw.rounded_rectangle([p_left, p_top, p_right, p_bottom], radius=p_r, fill=(30, 110, 235))
    draw.rounded_rectangle([p_left-1, p_top-1, p_right+1, p_bottom+1], radius=p_r+1, outline=(100, 190, 255), width=max(1, int(size * 0.01)))

    draw.arc([p_left, p_top - int(size * 0.09), p_right, p_top + int(size * 0.09)], start=0, end=180, fill=(140, 210, 255), width=int(size * 0.035))

    dash_y = int((p_top + p_bottom) * 0.5)
    dash_x1 = p_left + int(size * 0.06)
    dash_x2 = p_right - int(size * 0.06)
    curr_x = dash_x1
    dash_len = max(2, int(size * 0.02))
    gap_len = max(2, int(size * 0.015))
    while curr_x < dash_x2:
        draw.line([(curr_x, dash_y), (min(curr_x + dash_len, dash_x2), dash_y)], fill=(180, 220, 255), width=max(1, int(size * 0.008)))
        curr_x += dash_len + gap_len

    c_left = int(size * 0.28)
    c_top = int(size * 0.16)
    c_right = int(size * 0.72)
    c_bottom = int(size * 0.54)
    draw.rounded_rectangle([c_left, c_top, c_right, c_bottom], radius=int(size * 0.07), fill=(255, 255, 255))
    draw.rounded_rectangle([c_left, c_top, c_right, c_bottom], radius=int(size * 0.07), outline=(220, 230, 255), width=max(1, int(size * 0.008)))

    tri_x1 = int(size * 0.43)
    tri_y1 = int(size * 0.25)
    tri_x2 = int(size * 0.43)
    tri_y2 = int(size * 0.45)
    tri_x3 = int(size * 0.61)
    tri_y3 = int(size * 0.35)
    draw.polygon([(tri_x1, tri_y1), (tri_x2, tri_y2), (tri_x3, tri_y3)], fill=(15, 90, 225))

    b_cx = int(size * 0.73)
    b_cy = int(size * 0.58)
    b_rad = int(size * 0.13)
    draw.ellipse([b_cx - b_rad, b_cy - b_rad, b_cx + b_rad, b_cy + b_rad], fill=(255, 255, 255))
    draw.ellipse([b_cx - b_rad, b_cy - b_rad, b_cx + b_rad, b_cy + b_rad], outline=(20, 100, 230), width=max(1, int(size * 0.012)))

    arrow_x = b_cx
    arrow_y = b_cy - int(size * 0.01)
    arrow_w = int(size * 0.04)
    draw.line([(arrow_x, arrow_y - int(size * 0.05)), (arrow_x, arrow_y + int(size * 0.02))], fill=(15, 90, 225), width=max(2, int(size * 0.014)))
    draw.polygon([
        (arrow_x - arrow_w, arrow_y),
        (arrow_x + arrow_w, arrow_y),
        (arrow_x, arrow_y + int(size * 0.05))
    ], fill=(15, 90, 225))

    return img

def main():
    densities = {
        'mipmap-mdpi': 48,
        'mipmap-hdpi': 72,
        'mipmap-xhdpi': 96,
        'mipmap-xxhdpi': 144,
        'mipmap-xxxhdpi': 192
    }
    
    for folder, size in densities.items():
        dir_path = f"/app/src/main/res/{folder}"
        os.makedirs(dir_path, exist_ok=True)
        img = render_icon(size)
        img.save(os.path.join(dir_path, "ic_launcher.png"), "PNG")
        img.save(os.path.join(dir_path, "ic_launcher_round.png"), "PNG")
        img.save(os.path.join(dir_path, "ic_launcher_foreground.png"), "PNG")

    drawable_dir = "/app/src/main/res/drawable"
    os.makedirs(drawable_dir, exist_ok=True)
    fg_img = render_icon(432)
    fg_img.save(os.path.join(drawable_dir, "ic_launcher_foreground.png"), "PNG")
    
    high_res = render_icon(512)
    high_res.save(os.path.join(drawable_dir, "videopocket_play_store_512.png"), "PNG")
    print("Rendered exact assets successfully.")

if __name__ == '__main__':
    main()
