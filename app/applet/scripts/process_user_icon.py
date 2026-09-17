import os
from PIL import Image

def process_icon():
    src_path = "app/src/main/res/drawable/icon_launcher.png"
    if not os.path.exists(src_path):
        print(f"Error: {src_path} not found.")
        return
    
    img = Image.open(src_path).convert("RGBA")
    print(f"Loaded source icon: {img.size}")

    densities = {
        'mipmap-mdpi': 48,
        'mipmap-hdpi': 72,
        'mipmap-xhdpi': 96,
        'mipmap-xxhdpi': 144,
        'mipmap-xxxhdpi': 192
    }
    
    for folder, size in densities.items():
        dir_path = f"app/src/main/res/{folder}"
        os.makedirs(dir_path, exist_ok=True)
        resized = img.resize((size, size), Image.Resampling.LANCZOS)
        resized.save(os.path.join(dir_path, "ic_launcher.png"), "PNG")
        resized.save(os.path.join(dir_path, "ic_launcher_round.png"), "PNG")
        resized.save(os.path.join(dir_path, "ic_launcher_foreground.png"), "PNG")
        print(f"Generated {folder} ({size}x{size})")

    drawable_dir = "app/src/main/res/drawable"
    os.makedirs(drawable_dir, exist_ok=True)
    play_img = img.resize((512, 512), Image.Resampling.LANCZOS)
    play_img.save(os.path.join(drawable_dir, "videopocket_play_store_512.png"), "PNG")
    print("Generated videopocket_play_store_512.png (512x512)")

if __name__ == '__main__':
    process_icon()
