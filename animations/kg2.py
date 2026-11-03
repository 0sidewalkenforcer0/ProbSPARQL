import numpy as np
from manim import *

from styles import *

FONT = "Avenir Next"
MONO = "Menlo"
WARM = "#f97316"
R = 0.34


def backdrop():
    dots = VGroup(*[
        Dot(np.array([x, y, 0]), radius=0.022, color=MUTED).set_opacity(0.16)
        for x in np.arange(-8.0, 8.01, 0.8) for y in np.arange(-4.8, 4.81, 0.8)
    ])
    return dots


def _label(text, direction, anchor, font_size):
    t = Text(text, font=FONT, font_size=font_size, color=TEXT)
    return t.next_to(anchor, direction, buff=0.14)


def gnode(label, kind, pos, label_dir=DOWN, font_size=22, uncertainty=None):
    color = {"instance": INSTANCE, "class": CLASS, "ghost": MUTED}[kind]
    core = Circle(R, stroke_color=WHITE, stroke_width=1.5, stroke_opacity=0.55,
                  fill_color=color, fill_opacity=1)
    halo = VGroup(*[Circle(R * k, stroke_width=0, fill_color=color, fill_opacity=o)
                    for k, o in ((2.0, 0.04), (1.6, 0.08), (1.28, 0.15))])
    parts = [halo, core]
    mark = None
    ring = None
    if kind == "ghost":
        core.set_fill(BACKGROUND, 1).set_stroke(MUTED, 2, 0.9)
        halo.set_opacity(0)
        mark = Text("?", font=FONT, font_size=24, color=MUTED)
        parts.append(mark)
    if uncertainty is not None:
        ring = Circle(R * (1 + 0.8 * uncertainty), stroke_color=color, stroke_width=2, stroke_opacity=0.6)
        ring.set_fill(color, 0.07)
        parts.insert(0, ring)
    text = _label(label, label_dir, core, font_size)
    group = VGroup(*parts, text)
    group.core, group.halo, group.text, group.mark, group.ring = core, halo, text, mark, ring
    group.kind, group.u = kind, uncertainty
    group.move_to(np.array([pos[0], pos[1], 0.0]))
    return group


def ripple(node, color=INSTANCE):
    r = Circle(R, stroke_color=color, stroke_width=3, stroke_opacity=0.9).move_to(node.core)
    return AnimationGroup(r.animate.scale(3.2).set_stroke(opacity=0), run_time=1.1, rate_func=rush_from), r


def gedge(a, b, label=None, color=MUTED, opacity=0.85, width=2.6):
    start, end = a.core.get_center(), b.core.get_center()
    d = (end - start) / np.linalg.norm(end - start)
    arrow = Arrow(start + d * (R + 0.08), end - d * (R + 0.1), buff=0, stroke_width=width,
                  color=color, tip_length=0.2, max_tip_length_to_length_ratio=0.35)
    arrow.set_opacity(opacity)
    group = VGroup(arrow)
    group.arrow, group.start, group.end = arrow, start, end
    group.pill = None
    if label:
        t = Text(label, font=FONT, font_size=18, color=MUTED)
        t.move_to(arrow.get_center())
        bg = BackgroundRectangle(t, color=BACKGROUND, fill_opacity=0.92, buff=0.05)
        pill = VGroup(bg, t)
        group.add(pill)
        group.pill = pill
    return group


def flow(edge, color=INSTANCE, reverse=False, run_time=1.4):
    start, end = edge.arrow.get_start(), edge.arrow.get_end()
    a, b = (end, start) if reverse else (start, end)
    path = Line(a, b)
    dot = Dot(radius=0.09, color=color)
    glow = Circle(0.22, stroke_width=0, fill_color=color, fill_opacity=0.3)
    comet = VGroup(glow, dot).move_to(a)
    return Succession(FadeIn(comet, run_time=0.1), MoveAlongPath(comet, path, run_time=run_time, rate_func=smooth),
                      FadeOut(comet, run_time=0.15))


def code_panel(title, width=4.3, height=4.9, center=(4.75, 0.3)):
    box = RoundedRectangle(corner_radius=0.18, width=width, height=height, stroke_color="#1e293b",
                           stroke_width=2, fill_color="#0d1424", fill_opacity=0.96)
    head = Text(title, font=FONT, font_size=18, color=MUTED).move_to(box.get_top() + DOWN * 0.38)
    rule = Line(box.get_left() + RIGHT * 0.25 + UP * (height / 2 - 0.7), box.get_right() + LEFT * 0.25 + UP * (height / 2 - 0.7),
                stroke_color="#1e293b", stroke_width=2)
    panel = VGroup(box, head, rule).move_to(np.array([center[0], center[1], 0]))
    panel.box = box
    panel.cursor = box.get_top() + DOWN * 1.05 + LEFT * (width / 2 - 0.3)
    panel.lines = []
    return panel


def code_line(panel, text, color=TEXT, font_size=13):
    indent = len(text) - len(text.lstrip(" "))
    t = Text(text.strip() or " ", font=MONO, font_size=font_size, color=color)
    t.move_to(panel.cursor + RIGHT * 0.105 * indent, aligned_edge=LEFT)
    panel.cursor = panel.cursor + DOWN * 0.34
    panel.lines.append(t)
    return t


def ring_scale(node, u):
    return R * (1 + 0.8 * u) / (node.ring.width / 2)


def to_known(node, u, color=INSTANCE):
    anims = [
        node.core.animate.set_fill(color, 1).set_stroke(WHITE, 1.5, 0.55),
        node.halo.animate.set_fill(color, 0.1),
        node.ring.animate.scale(ring_scale(node, u)).set_stroke(color, 2, 0.6).set_fill(color, 0.07),
    ]
    if node.mark is not None:
        anims.append(FadeOut(node.mark))
    return anims


def to_ghost(node):
    if node.mark is None:
        node.mark = Text("?", font=FONT, font_size=24, color=MUTED).move_to(node.core)
        node.add(node.mark)
        node.mark.set_opacity(0)
    return [
        node.core.animate.set_fill(BACKGROUND, 1).set_stroke(MUTED, 2, 0.9),
        node.halo.animate.set_fill(MUTED, 0),
        node.ring.animate.scale(ring_scale(node, 1.0)).set_stroke(MUTED, 2, 0.6).set_fill(MUTED, 0.05),
        node.mark.animate.set_opacity(1),
    ]


def reset_panel(panel):
    old = list(panel.lines)
    panel.lines = []
    panel.cursor = panel.box.get_top() + DOWN * 1.05 + LEFT * (panel.box.width / 2 - 0.3)
    return FadeOut(VGroup(*old)) if old else Wait(0.01)


def recolor(node, color):
    node.core.set_fill(color)
    node.halo.set_fill(color)
    node.text.set_color(color)
    return node


def dim(node):
    return [
        node.core.animate.set_fill(MUTED, 0.3).set_stroke(MUTED, 2, 0.6),
        node.halo.animate.set_fill(MUTED, 0),
        node.text.animate.set_color(MUTED),
    ]
