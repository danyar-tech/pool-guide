"""Regression check on a real screenshot from the user's game: one green solid ball, the game's ghost ring is NOT a ball."""
import os, cv2
from detect import detect_v2, assign_kinds
img = cv2.cvtColor(cv2.imread(os.path.join(os.path.dirname(__file__), 'real', 'user_game_guideline.jpg')), cv2.COLOR_BGR2RGB)
table = dict(left=0, top=48, right=373, bottom=225, pockets=[(-500, -500)], pr=10)
for R in (21, 23, 25):
    balls = detect_v2(img, table, R)
    kinds = assign_kinds(balls)
    assert len(balls) == 1 and kinds[0] == 'solid' and abs(balls[0][0] - 201) < 5, (R, balls, kinds)
print('real screenshot OK')
