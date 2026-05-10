import re

with open('app/src/main/java/com/ryotn/videocompressor/ui/MainScreen.kt', 'r') as f:
    content = f.read()

# First we need to find where the estimated size was shown.
# Since it was requested to be in CompressionOptionsContent, we'll add it there.
