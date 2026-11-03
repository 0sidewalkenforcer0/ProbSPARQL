from manim import *

from kg2 import *
from styles import *

POS = {
    "class": (-4.6, 1.5), "product": (-3.4, -0.3),
    "gear": (-0.6, 1.6), "shaft": (-0.6, -2.15),
    "vib": (2.6, 3.1), "hard_g": (2.6, 1.6), "dmg": (2.6, 0.1),
    "dia": (2.6, -1.4), "hard_s": (2.6, -2.9),
}
SCALE, SHIFT = 0.8, np.array([-2.35, 0.0, 0.0])


def place(mobs):
    g = VGroup(*mobs)
    g.scale(SCALE, about_point=ORIGIN).shift(SHIFT)


def make_graph(attr_kind, uncertainty):
    N = {
        "class": gnode("AngleGrinder", "class", POS["class"], label_dir=UP),
        "product": gnode("Product A", "instance", POS["product"]),
        "gear": gnode("Gear", "instance", POS["gear"]),
        "shaft": gnode("Shaft", "instance", POS["shaft"]),
    }
    for k, name in (("vib", "vibration"), ("hard_g", "hardness"), ("dmg", "damage"), ("dia", "diameter"), ("hard_s", "hardness")):
        N[k] = gnode(name, attr_kind, POS[k], label_dir=DOWN, uncertainty=uncertainty[k])
    E = {
        "type": gedge(N["product"], N["class"], "type"),
        "gear": gedge(N["product"], N["gear"], "hasPart"),
        "shaft": gedge(N["product"], N["shaft"], "hasPart"),
    }
    for k, parent in (("vib", "gear"), ("hard_g", "gear"), ("dmg", "gear"), ("dia", "shaft"), ("hard_s", "shaft")):
        E[k] = gedge(N[parent], N[k])
    return N, E


def header(text):
    return Text(text, font=FONT, font_size=26, color=TEXT).to_corner(UL, buff=0.4)


def caption(text):
    return Text(text, font=FONT, font_size=26, color=TEXT).to_edge(DOWN, buff=0.35)


class KgBlankInstance(Scene):
    def construct(self):
        self.camera.background_color = BACKGROUND
        self.add(backdrop())
        N, E = make_graph("ghost", {k: 1.0 for k in ("vib", "hard_g", "dmg", "dia", "hard_s")})
        for k in ("vib", "hard_g", "dmg", "dia", "hard_s"):
            E[k].set_opacity(0.35)
        place([*N.values(), *E.values()])
        panel = code_panel("RDF  ·  what the graph stores")
        title, cap = header("Scene 2  ·  The factory knows nothing"), caption("In the knowledge graph, this product is a blank instance.")

        self.play(FadeIn(title), FadeIn(panel), run_time=1)

        l1 = code_line(panel, ":productA a ag:AngleGrinder .", INSTANCE)
        anim, r = ripple(N["product"])
        self.play(FadeIn(N["product"], scale=0.4), AddTextLetterByLetter(l1, time_per_char=0.03), anim, run_time=1.4)
        self.remove(r)
        self.play(FadeIn(N["class"], shift=RIGHT * 0.2), GrowArrow(E["type"].arrow), FadeIn(E["type"].pill), run_time=1.1)

        l2 = code_line(panel, ":productA ag:hasPart", INSTANCE)
        l2b = code_line(panel, "    :gear , :shaft .", INSTANCE)
        self.play(AddTextLetterByLetter(l2, time_per_char=0.03), AddTextLetterByLetter(l2b, time_per_char=0.03),
                  LaggedStart(
                      AnimationGroup(FadeIn(N["gear"], scale=0.4), GrowArrow(E["gear"].arrow), FadeIn(E["gear"].pill)),
                      AnimationGroup(FadeIn(N["shaft"], scale=0.4), GrowArrow(E["shaft"].arrow), FadeIn(E["shaft"].pill)),
                      lag_ratio=0.5),
                  run_time=2)
        self.wait(0.5)

        c1 = code_line(panel, "")
        c2 = code_line(panel, "# AngleGrinder expects vibration,", MUTED)
        c3 = code_line(panel, "#   hardness, damage, diameter", MUTED)
        c4 = code_line(panel, "# no triples yet => value unknown", WARM)
        slots = ["vib", "hard_g", "dmg", "dia", "hard_s"]
        self.play(
            AddTextLetterByLetter(c2, time_per_char=0.02), AddTextLetterByLetter(c3, time_per_char=0.02),
            LaggedStart(*[AnimationGroup(FadeIn(N[k], scale=0.5), FadeIn(E[k])) for k in slots], lag_ratio=0.3),
            run_time=3,
        )
        self.play(AddTextLetterByLetter(c4, time_per_char=0.03), FadeIn(cap), run_time=1.5)

        rings = [N[k].ring for k in slots]
        self.play(*[Indicate(r, color=MUTED, scale_factor=1.12) for r in rings], run_time=1.6)
        self.wait(2)


class KgBeliefUpdate(Scene):
    def construct(self):
        self.camera.background_color = BACKGROUND
        self.add(backdrop())
        U0 = {"vib": 0.8, "hard_g": 0.5, "dmg": 0.75}
        N, E = make_graph("instance", {**U0, "dia": 0.5, "hard_s": 0.5})
        for k in ("shaft", "dia", "hard_s"):
            del N[k]
        for k in ("shaft", "dia", "hard_s"):
            del E[k]

        test = gnode("test_7", "instance", (5.4, 3.1), label_dir=UP)
        belief = gnode("belief_L1", "instance", (5.4, 1.2), label_dir=DOWN)
        e_meas = gedge(test, N["vib"], "measures")
        e_b1 = gedge(belief, N["vib"], "wasDerivedFrom", color=LIKELIHOOD, opacity=0.95)
        e_b2 = gedge(belief, test, None, color=LIKELIHOOD, opacity=0.95)
        place([*N.values(), *E.values(), test, belief, e_meas, e_b1, e_b2])

        panel = code_panel("INSERT DATA  ·  ProbSPARQL store")
        title, cap = header("Scene 12  ·  Belief update after the functional test"), caption("Prior and measurement are fused into an updated belief.")
        self.add(*E.values(), *N.values())
        self.play(FadeIn(title), FadeIn(panel), FadeIn(cap), run_time=1.2)

        a1 = code_line(panel, "INSERT DATA {", MUTED)
        a2 = code_line(panel, "  :test_7 a cfm:Measurement ;", INSTANCE)
        a3 = code_line(panel, "    cfm:measuresCharacteristic", INSTANCE)
        a3b = code_line(panel, "      :vibration .", INSTANCE)
        self.play(AddTextLetterByLetter(a1, time_per_char=0.03), run_time=0.6)
        anim, r = ripple(test)
        self.play(FadeIn(test, scale=0.4), anim, AddTextLetterByLetter(a2, time_per_char=0.03), run_time=1.4)
        self.remove(r)
        self.play(GrowArrow(e_meas.arrow), FadeIn(e_meas.pill), AddTextLetterByLetter(a3, time_per_char=0.025), AddTextLetterByLetter(a3b, time_per_char=0.025), run_time=1.6)
        self.wait(0.4)

        a4 = code_line(panel, "  :belief_L1", LIKELIHOOD)
        a4b = code_line(panel, "    prov:wasDerivedFrom", LIKELIHOOD)
        a5 = code_line(panel, "      :vibration_L0 , :test_7 .", LIKELIHOOD)
        a6 = code_line(panel, "}", MUTED)
        anim, r = ripple(belief, LIKELIHOOD)
        self.play(FadeIn(belief, scale=0.4), anim, AddTextLetterByLetter(a4, time_per_char=0.03), AddTextLetterByLetter(a4b, time_per_char=0.03), run_time=1.4)
        self.remove(r)
        self.play(GrowArrow(e_b1.arrow), FadeIn(e_b1.pill), GrowArrow(e_b2.arrow),
                  AddTextLetterByLetter(a5, time_per_char=0.03), AddTextLetterByLetter(a6, time_per_char=0.05), run_time=1.6)
        self.play(flow(e_b1, LIKELIHOOD, reverse=True), flow(e_b2, LIKELIHOOD, reverse=True), run_time=1.6)

        newr = lambda n, u: Circle(R * (1 + 0.8 * u)).get_width() / n.ring.get_width()
        self.play(N["vib"].ring.animate.scale(newr(N["vib"], 0.12)),
                  Flash(N["vib"].core.get_center(), color=INSTANCE, line_length=0.18, flash_radius=0.5), run_time=1.6)

        self.play(flow(E["vib"], WARM, reverse=True, run_time=1.0), run_time=1.0)
        self.play(N["gear"].core.animate.set_fill(WARM, 1), N["gear"].halo.animate.set_fill(WARM),
                  Flash(N["gear"].core.get_center(), color=WARM, line_length=0.18, flash_radius=0.5), run_time=1.0)
        self.play(flow(E["dmg"], WARM, run_time=1.0), run_time=1.0)
        value = ValueTracker(0)
        number = DecimalNumber(0, num_decimal_places=2, font_size=30, color=WARM)
        number.add_updater(lambda m: m.set_value(value.get_value()))
        tag = Text("P(damage)", font=FONT, font_size=16, color=MUTED)
        readout = VGroup(tag, number).arrange(RIGHT, buff=0.18)
        readout.next_to(N["dmg"].text, DOWN, buff=0.2)
        self.play(N["dmg"].ring.animate.scale(newr(N["dmg"], 0.1)).set_stroke(WARM),
                  N["dmg"].core.animate.set_fill(WARM, 1), FadeIn(readout), value.animate.set_value(0.98), run_time=2)
        self.wait(2.5)
