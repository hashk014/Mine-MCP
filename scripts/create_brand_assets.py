"""Draw the original vector logo and rasterize the same geometry for distribution."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'docs/media'
DEST.mkdir(parents=True, exist_ok=True)
svg = '''<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
<rect width="512" height="512" rx="100" fill="#111c19"/>
<g stroke="#26382e" fill="none"><path d="M40 156H472M40 256H472M40 356H472M156 40V472M256 40V472M356 40V472"/></g>
<path d="M256 106 386 180 256 254 126 180Z" fill="#d9ff89"/>
<path d="M126 180 256 254 256 398 126 324Z" fill="#6f9f29"/>
<path d="M256 254 386 180 386 324 256 398Z" fill="#b4e846"/>
<path d="M256 136 334 180 256 225 178 180Z" fill="#111c19"/>
<path d="M256 168 287 186 256 204 225 186Z" fill="#d9ff89"/>
<g fill="none" stroke="#f1f2eb" stroke-width="10" stroke-linejoin="round"><path d="M386 249H435V106H396"/><path d="M126 271H78V371"/></g>
<g fill="#f1f2eb"><circle cx="396" cy="106" r="17"/><circle cx="78" cy="385" r="17"/></g>
<g fill="#111c19"><circle cx="396" cy="106" r="6"/><circle cx="78" cy="385" r="6"/></g>
</svg>'''
(DEST / 'logo.svg').write_text(svg, encoding='utf-8')
scale = 3
im = Image.new('RGB', (512 * scale, 512 * scale), '#111c19')
d = ImageDraw.Draw(im)
def polygon(points, fill): d.polygon([(x*scale,y*scale) for x,y in points], fill=fill)
for value in (156,256,356):
    d.line((40*scale,value*scale,472*scale,value*scale),fill='#26382e',width=scale)
    d.line((value*scale,40*scale,value*scale,472*scale),fill='#26382e',width=scale)
polygon([(256,106),(386,180),(256,254),(126,180)], '#d9ff89')
polygon([(126,180),(256,254),(256,398),(126,324)], '#6f9f29')
polygon([(256,254),(386,180),(386,324),(256,398)], '#b4e846')
polygon([(256,136),(334,180),(256,225),(178,180)], '#111c19')
polygon([(256,168),(287,186),(256,204),(225,186)], '#d9ff89')
for points in [[(386,249),(435,249),(435,106),(396,106)],[(126,271),(78,271),(78,371)]]:
    d.line([(x*scale,y*scale) for x,y in points],fill='#f1f2eb',width=10*scale,joint='curve')
for x,y in [(396,106),(78,385)]:
    for radius,fill in [(17,'#f1f2eb'),(6,'#111c19')]: d.ellipse(((x-radius)*scale,(y-radius)*scale,(x+radius)*scale,(y+radius)*scale),fill=fill)
im.resize((512,512),Image.Resampling.LANCZOS).save(DEST/'logo.png')
print('Created original logo.svg and 512px logo.png')
