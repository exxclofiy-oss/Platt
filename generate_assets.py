"""Generate transparent pixel-art trim UV textures and 1.21.11 resource/data packs.
All textures are source assets, no external font/image download needed.
"""
import json, zipfile, shutil
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parent
DATA=ROOT/'datapack'
RES=ROOT/'resourcepack'
for p in (DATA,RES):p.mkdir(exist_ok=True)
def write(path,obj):
    dest=ROOT/path;dest.parent.mkdir(parents=True,exist_ok=True)
    dest.write_text(json.dumps(obj,indent=2)+'\n',encoding='utf8')

write(Path('datapack/pack.mcmeta'),{'pack':{'description':'Platt - 1.21.11 patterns and materials','min_format':94.1,'max_format':94.1}})
write(Path('resourcepack/pack.mcmeta'),{'pack':{'description':'Platt 13 colorways - Paper 1.21.11','min_format':75.0,'max_format':75.0}})
write(Path('datapack/data/platt/trim_pattern/platt.json'),{'asset_id':'platt:platt','description':{'text':'Platt','color':'aqua'},'decal':False})
for name,title in [('breeze','Breeze Rod'),('echo','Echo Shard')]:
    write(Path(f'datapack/data/platt/trim_material/{name}.json'),{'asset_name':name,'description':{'text':title,'color': 'aqua'}})
colors={
    'lapis':('#163f81','#347cf5','#b5dbff'),
    'iron':('#657583','#b9cbd5','#f7fbff'),
    'redstone':('#631a21','#e12d39','#ffb08c'),
    'copper':('#7c392a','#cf743f','#ffd8a3'),
    'gold':('#8f5817','#e0b432','#fff0a3'),
    'emerald':('#1a633c','#44d477','#b1ffd0'),
    'diamond':('#176e81','#49d6db','#b9fff3'),
    'netherite':('#241c2a','#685b70','#b2a4b0'),
    'quartz':('#7d7987','#d8d6e9','#ffffff'),
    'amethyst':('#48326d','#ad76db','#e2c5ff'),
    'resin':('#77360e','#e87f16','#ffc476'),
    'breeze':('#3b6686','#99daf1','#ecfdff'),
    'echo':('#073e49','#1e99a4','#9aeaff'),
}

def uv(kind):
    im=Image.new('RGBA',(64,32),(0,0,0,0)); d=ImageDraw.Draw(im)
    # A repeatable stylized platt sigil mapped onto head, body, arm and leg faces.
    # Neutral alpha mask recolored per material without relying on Mojang base palette.
    c1=(0,0,0,0);lo=(80,80,80,255);md=(155,155,155,255);hi=(235,235,235,255)
    def diamond(x,y,r=3):
        d.line([(x,y-r),(x+r,y),(x,y+r),(x-r,y),(x,y-r)],fill=md,width=1)
        d.point((x,y),fill=hi)
    # helmet crown/head faces (top 16 pixels of humanoid texture)
    for x in (8,16,24):
        d.line([(x-2,3),(x,2),(x+2,3)],fill=md,width=1)
        d.point((x,4),fill=hi)
    d.line([(2,9),(6,7),(10,9),(14,7),(18,9),(22,7),(26,9),(30,7)],fill=md,width=1)
    for x in (4,12,20,28):diamond(x,12,2)
    # arms and chest distinct UV panels
    for off in (0,16,32,48):
        if off+13<=64:
            d.line([(off+2,18),(off+5,20),(off+8,18),(off+11,20)],fill=lo)
            d.line([(off+3,24),(off+6,22),(off+9,24)],fill=md)
            diamond(off+7,27,2)
    # torso central glyph (always visible on front torso UV area)
    d.line([(20,17),(24,20),(28,17),(32,20),(36,17)],fill=hi)
    d.line([(20,30),(24,27),(28,30),(32,27),(36,30)],fill=md)
    diamond(28,24,4)
    # leggings atlas: emphasize vertical racing stripes and small sigils
    if kind=='leggings':
        for off in (0,16,32,48):
            d.line([(off+5,17),(off+5,29)],fill=md)
            d.line([(off+7,18),(off+7,30)],fill=hi)
            diamond(off+9,22,2)
    return im

def tint(src,triplet):
    out=Image.new('RGBA',src.size,(0,0,0,0)); data=[]
    rgb=[tuple(int(c.lstrip('#')[k:k+2],16) for k in (0,2,4)) for c in triplet]
    for r,g,b,a in src.getdata():
        if not a: data.append((0,0,0,0));continue
        chosen=rgb[0] if r<120 else rgb[1] if r<190 else rgb[2]
        data.append((*chosen,255))
    out.putdata(data);return out

# Use explicit armor-trim atlas SINGLE sprites so colors work without Mojang palette dependency.
assets=RES/'assets/platt/textures/trims/entity'
models=[]
for name,colorset in colors.items():
    for kind in ('humanoid','humanoid_leggings'):
        dest=assets/kind/f'platt_{name}.png'
        dest.parent.mkdir(parents=True,exist_ok=True)
        tint(uv('leggings' if kind.endswith('leggings') else 'normal'),colorset).save(dest)
        models.append({'type':'minecraft:single','resource':f'platt:trims/entity/{kind}/platt_{name}'})
write(Path('resourcepack/assets/minecraft/atlases/armor_trims.json'),{'replace':False,'sources':models})

# Vanilla armor item icons use inventory trim sprites. Custom colors need custom item sprites.
item_atlas=[]
for m in ('breeze','echo'):
    lo,md,hi=colors[m]
    palette=[tuple(int(col.lstrip('#')[k:k+2],16) for k in (0,2,4))+(255,) for col in (lo,md,hi)]
    for armor in ('helmet','chestplate','leggings','boots'):
        img=Image.new('RGBA',(16,16));draw=ImageDraw.Draw(img)
        if armor=='helmet':
            draw.arc((2,1,13,12),180,360,fill=palette[1],width=2)
            draw.line((2,7,2,11,13,11,13,7),fill=palette[1],width=1)
            draw.line((4,9,11,9),fill=palette[2],width=1)
        elif armor=='chestplate':
            draw.line((2,3,5,2,7,5,9,2,13,3,14,11,11,13,5,13,2,11,2,3),fill=palette[1],width=1)
            draw.polygon([(8,5),(10,8),(8,11),(6,8)],outline=palette[2])
        elif armor=='leggings':
            draw.line((3,2,12,2,12,13,9,13,8,7,7,7,6,13,3,13,3,2),fill=palette[1],width=1)
            draw.line((5,4,10,4),fill=palette[2])
        else:
            draw.line((3,3,3,11,7,11,7,8,9,8,9,11,13,11,13,3),fill=palette[1],width=2)
            draw.line((3,10,7,10),fill=palette[2])
        dest=RES/f'assets/minecraft/textures/trims/items/{armor}_trim_{m}.png'
        dest.parent.mkdir(parents=True,exist_ok=True)
        img.save(dest)
        item_atlas.append({'type':'minecraft:single','resource':f'minecraft:trims/items/{armor}_trim_{m}'})
write(Path('resourcepack/assets/minecraft/atlases/items.json'),{'replace':False,'sources':item_atlas})

# Custom Smithing Template inventory model, pixel-art icon.
icon=Image.new('RGBA',(16,16));dd=ImageDraw.Draw(icon)
dd.rounded_rectangle((1,1,14,14),radius=2,fill=(34,45,64,255),outline=(130,202,221,255),width=1)
dd.line([(7,2),(12,7),(7,12),(2,7),(7,2)],fill=(167,236,251,255),width=2)
dd.line([(7,4),(10,7),(7,10),(4,7),(7,4)],fill=(25,129,179,255),width=1)
dd.point((7,7),fill=(255,255,255,255))
dd.point((3,3),fill=(253,221,133,255));dd.point((11,11),fill=(253,221,133,255))
path=RES/'assets/platt/textures/item/platt_template.png';path.parent.mkdir(parents=True,exist_ok=True);icon.save(path)
write(Path('resourcepack/assets/platt/items/platt_template.json'),{'model':{'type':'minecraft:model','model':'platt:item/platt_template'}})
write(Path('resourcepack/assets/platt/models/item/platt_template.json'),{'parent':'minecraft:item/generated','textures':{'layer0':'platt:item/platt_template'}})
write(Path('resourcepack/assets/platt/lang/en_us.json'),{'trim_pattern.platt.platt':'Platt','trim_material.platt.breeze':'Breeze Rod','trim_material.platt.echo':'Echo Shard'})

# Standalone asset preview with 13 material colorways and iconic template.
preview=Image.new('RGB',(1050,610),(15,21,32));p=ImageDraw.Draw(preview)
try:
    font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',19)
    title=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',31)
except OSError:font=ImageFont.load_default();title=font
p.text((28,20),'PLATT  /  13 MATERIALS',font=title,fill='#c6efff')
p.text((30,64),'Original 64×32 armor overlay textures • 1.21.11',font=font,fill='#8998ad')
for idx,(name,pal) in enumerate(colors.items()):
    x=25+(idx%5)*203;y=114+(idx//5)*153
    p.rounded_rectangle((x,y,x+182,y+133),radius=9,fill=(30,42,59),outline=(66,86,110),width=1)
    tex=Image.open(assets/f'humanoid/platt_{name}.png').resize((160,80),Image.Resampling.NEAREST)
    preview.paste(tex,(x+10,y+12),tex)
    p.text((x+10,y+102),name.upper(),font=font,fill='#eeeeff')
preview.save(ROOT/'artwork/trim-preview.png')

for src,filename in ((DATA,'Platt_Datapack_1.21.11.zip'),(RES,'Platt_ResourcePack_1.21.11.zip')):
    with zipfile.ZipFile(ROOT/filename,'w',compression=zipfile.ZIP_DEFLATED) as z:
        for f in src.rglob('*'):
            if f.is_file():z.write(f,f.relative_to(src).as_posix())
print('Generated',len(colors),'materials and',len(models),'armor overlay textures.')
