#!/usr/bin/env python3
"""Lucide の SVG を Android の VectorDrawable に変換する（線のアイコン用）。
使い方: python3 tools/lucide2vector.py <lucide-static/icons ディレクトリ> <出力先 res/drawable> name1 name2 ...
"""
import re, sys, pathlib, xml.etree.ElementTree as ET

def num(v): return float(v) if v else 0.0

def el_to_path(el):
    tag = el.tag.split('}')[-1]
    a = el.attrib
    if tag == 'path':
        return a['d']
    if tag == 'line':
        return f"M{a['x1']},{a['y1']} L{a['x2']},{a['y2']}"
    if tag in ('polyline', 'polygon'):
        pts = re.findall(r'-?[\d.]+', a['points'])
        pairs = [f"{pts[i]},{pts[i+1]}" for i in range(0, len(pts), 2)]
        d = 'M' + ' L'.join(pairs)
        return d + (' Z' if tag == 'polygon' else '')
    if tag == 'circle':
        cx, cy, r = num(a['cx']), num(a['cy']), num(a['r'])
        return f"M{cx-r},{cy} a{r},{r} 0 1,0 {2*r},0 a{r},{r} 0 1,0 {-2*r},0"
    if tag == 'ellipse':
        cx, cy, rx, ry = num(a['cx']), num(a['cy']), num(a['rx']), num(a['ry'])
        return f"M{cx-rx},{cy} a{rx},{ry} 0 1,0 {2*rx},0 a{rx},{ry} 0 1,0 {-2*rx},0"
    if tag == 'rect':
        x, y, w, h = num(a.get('x')), num(a.get('y')), num(a['width']), num(a['height'])
        rx = num(a.get('rx') or a.get('ry')); ry = num(a.get('ry') or a.get('rx'))
        if rx == 0:
            return f"M{x},{y} h{w} v{h} h{-w} Z"
        return (f"M{x+rx},{y} h{w-2*rx} a{rx},{ry} 0 0 1 {rx},{ry} v{h-2*ry} "
                f"a{rx},{ry} 0 0 1 {-rx},{ry} h{-(w-2*rx)} a{rx},{ry} 0 0 1 {-rx},{-ry} "
                f"v{-(h-2*ry)} a{rx},{ry} 0 0 1 {rx},{-ry} Z")
    raise ValueError('unsupported element ' + tag)

def convert(svg_text):
    root = ET.fromstring(svg_text)
    paths = [el_to_path(el) for el in root.iter() if el is not root and el.tag.split('}')[-1] != 'title']
    body = '\n'.join(
        f'    <path android:pathData="{d}" android:strokeColor="#FFFFFFFF" android:strokeWidth="2" '
        f'android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000" />'
        for d in paths)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Lucide (ISC License) https://lucide.dev -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp" android:height="24dp"\n'
            '    android:viewportWidth="24" android:viewportHeight="24">\n'
            f'{body}\n</vector>\n')

if __name__ == '__main__':
    src, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    for name in sys.argv[3:]:
        xml = convert((src / f'{name}.svg').read_text())
        (out / ('lc_' + name.replace('-', '_') + '.xml')).write_text(xml)
        print('ok', name)
