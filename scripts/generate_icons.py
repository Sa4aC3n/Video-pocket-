import os
from PIL import Image, ImageDraw

def create_icon(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    # Squircle background
    margin = int(size * 0.05)
    r = int(size * 0.25)
    box = [margin, margin, size - margin, size - margin]
    draw.rounded_rectangle(box, radius=r, fill=(30, 34, 170))
    
    # Inner gradient / gloss simulation
    draw.rounded_rectangle([margin + 4, margin + 4, size - margin - 4, size - margin - 4], radius=r - 4, outline=(79, 134, 247, 120), width=max(1, int(size * 0.015)))

    # Pocket body
    p_left = int(size * 0.25)
    p_top = int(size * 0.35)
    p_right = int(size * 0.75)
    p_bottom = int(size * 0.75)
    p_r = int(size * 0.1)
    
    draw.rounded_rectangle([p_left, p_top, p_right, p_bottom], radius=p_r, fill=(25, 118, 210))
    draw.rounded_rectangle([p_left - 2, p_top - 2, p_right + 2, p_bottom + 2], radius=p_r + 2, outline=(66, 165, 245), width=max(1, int(size * 0.01)))

    # Pocket opening / rim curve
    draw.arc([p_left, p_top - int(size * 0.08), p_right, p_top + int(size * 0.08)], start=0, end=180, fill=(100, 181, 246), width=int(size * 0.03))

    # White card with play button
    c_left = int(size * 0.32)
    c_top = int(size * 0.22)
    c_right = int(size * 0.68)
    c_bottom = int(size * 0.55)
    draw.rounded_rectangle([c_left, c_top, c_right, c_bottom], radius=int(size * 0.06), fill=(255, 255, 255))
    
    # Play triangle
    tri_points = [
        (int(size * 0.44), int(size * 0.32)),
        (int(size * 0.44), int(size * 0.45)),
        (int(size * 0.58), int(size * 0.385))
    ]
    draw.polygon(tri_points, fill=(13, 71, 161))

    # Download badge circle on the right
    b_cx = int(size * 0.70)
    b_cy = int(size * 0.55)
    b_rad = int(size * 0.12)
    draw.ellipse([b_cx - b_rad, b_cy - b_rad, b_cx + b_rad, b_cy + b_rad], fill=(255, 255, 255))
    draw.ellipse([b_cx - b_rad, b_cy - b_rad, b_cx + b_rad, b_cy + b_rad], outline=(21, 101, 192), width=max(1, int(size * 0.01)))

    # Download arrow inside badge
    arrow_x = b_cx
    arrow_y = b_cy
    arrow_w = int(size * 0.04)
    draw.line([(arrow_x, arrow_y - int(size * 0.04)), (arrow_x, arrow_y + int(size * 0.03))], fill=(21, 101, 192), width=max(2, int(size * 0.012)))
    draw.polygon([
        (arrow_x - arrow_w, arrow_y + int(size * 0.01)),
        (arrow_x + arrow_w, arrow_y + int(size * 0.01)),
        (arrow_x, arrow_y + int(size * 0.05))
    ], fill=(21, 101, 192))

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
        img = create_icon(size)
        img.save(os.path.join(dir_path, "ic_launcher.png"), "PNG")
        img.save(os.path.join(dir_path, "ic_launcher_round.png"), "PNG")
        print(f"Generated {folder} icon ({size}x{size})")

    # Also generate 512x512 Play Store asset
    play_store_dir = "/app/src/main/res/drawable"
    os.makedirs(play_store_dir, exist_ok=True)
    play_img = create_icon(512)
    play_img.save(os.path.join(play_store_dir, "videopocket_play_store_512.png"), "PNG")
    print("Generated 512x512 Play Store asset.")

if __name__ == '__main__':
    main()
