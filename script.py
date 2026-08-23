import re

with open('app/src/main/java/com/macrobase/app/feature/detail/FoodDetailScreen.kt', 'r', encoding='utf-8') as f:
    text = f.read()

# We'll completely replace the content.
