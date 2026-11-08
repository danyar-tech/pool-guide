import math, sys
from gen import make_scene
import gen
from detect import *
seeds = list(range(int(sys.argv[1]), int(sys.argv[2])))
bad=[];n=0;none=0;errs=[]
for seed in seeds:
  img,truth,table,r,stick=make_scene(seed)
  if stick is None: continue
  n+=1
  P=Prep(img,table,r); cue=[t for t in truth if t[2]==0][0]
  res=detect_stick2(P,cue,table)
  if res is None: none+=1; continue
  e=math.degrees(abs((res[0]-stick+math.pi)%(2*math.pi)-math.pi)); errs.append(e)
  if e>1: bad.append((seed,round(e,1),res[1],dict(gen.INFO)))
errs.sort()
print(n,'scenes; not found',none,'; wrong >1deg',len(bad), 'median %.3f p90 %.3f'%(errs[len(errs)//2],errs[int(len(errs)*.9)])); [print(b) for b in bad]
