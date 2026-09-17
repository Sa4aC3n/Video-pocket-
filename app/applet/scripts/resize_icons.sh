#!/bin/bash
SRC="app/src/main/res/drawable/icon_launcher.png"

if [ ! -f "$SRC" ]; then
    echo "Error: $SRC not found."
    exit 1
fi

echo "Resizing icons using ImageMagick..."

# mdpi (48x48)
mkdir -p app/src/main/res/mipmap-mdpi
convert "$SRC" -resize 48x48 app/src/main/res/mipmap-mdpi/ic_launcher.png
convert "$SRC" -resize 48x48 app/src/main/res/mipmap-mdpi/ic_launcher_round.png
convert "$SRC" -resize 48x48 app/src/main/res/mipmap-mdpi/ic_launcher_foreground.png

# hdpi (72x72)
mkdir -p app/src/main/res/mipmap-hdpi
convert "$SRC" -resize 72x72 app/src/main/res/mipmap-hdpi/ic_launcher.png
convert "$SRC" -resize 72x72 app/src/main/res/mipmap-hdpi/ic_launcher_round.png
convert "$SRC" -resize 72x72 app/src/main/res/mipmap-hdpi/ic_launcher_foreground.png

# xhdpi (96x96)
mkdir -p app/src/main/res/mipmap-xhdpi
convert "$SRC" -resize 96x96 app/src/main/res/mipmap-xhdpi/ic_launcher.png
convert "$SRC" -resize 96x96 app/src/main/res/mipmap-xhdpi/ic_launcher_round.png
convert "$SRC" -resize 96x96 app/src/main/res/mipmap-xhdpi/ic_launcher_foreground.png

# xxhdpi (144x144)
mkdir -p app/src/main/res/mipmap-xxhdpi
convert "$SRC" -resize 144x144 app/src/main/res/mipmap-xxhdpi/ic_launcher.png
convert "$SRC" -resize 144x144 app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png
convert "$SRC" -resize 144x144 app/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.png

# xxxhdpi (192x192)
mkdir -p app/src/main/res/mipmap-xxxhdpi
convert "$SRC" -resize 192x192 app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
convert "$SRC" -resize 192x192 app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png
convert "$SRC" -resize 192x192 app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png

# Play Store asset (512x512)
mkdir -p app/src/main/res/drawable
convert "$SRC" -resize 512x512 app/src/main/res/drawable/videopocket_play_store_512.png

echo "Icon resizing completed successfully."
