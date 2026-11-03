from manim import *

from kg2 import *
from styles import *

SCALE, SHIFT = 0.8, np.array([-2.35, 0.0, 0.0])


def place(mobs):
    VGroup(*mobs).scale(SCALE, about_point=ORIGIN).shift(SHIFT)


def header(text):
    return Text(text, font=FONT, font_size=26, color=TEXT).to_corner(UL, buff=0.4)


def caption(text):
    return Text(text, font=FONT, font_size=26, color=TEXT).to_edge(DOWN, buff=0.35)


def pop(scene_node):
    anim, r = ripple(scene_node)
    return anim, r


class KgHistoryPrior(Scene):
    def construct(self):
        self.camera.background_color = BACKGROUND
        self.add(backdrop())
        P = {"class": (-4.2, -2.0), "product": (-4.2, 1.6), "gear": (-1.4, 2.2), "vib": (2.4, 3.1),
             "h1": (-2.0, -0.8), "h2": (-2.0, -2.0), "h3": (-2.0, -3.2),
             "v1": (0.6, -0.8), "v2": (0.6, -2.0), "v3": (0.6, -3.2), "prior": (3.0, -2.0)}
        N = {
            "class": gnode("AngleGrinder", "class", P["class"], label_dir=DOWN, font_size=20),
            "product": gnode("Product A", "instance", P["product"]),
            "gear": gnode("Gear", "instance", P["gear"]),
            "vib": gnode("vibration", "ghost", P["vib"], uncertainty=1.0),
            "h1": gnode("past_1", "instance", P["h1"]), "h2": gnode("past_2", "instance", P["h2"]),
            "h3": gnode("past_40", "instance", P["h3"]),
            "v1": gnode("v1", "instance", P["v1"]), "v2": gnode("v2", "instance", P["v2"]), "v3": gnode("v40", "instance", P["v3"]),
            "prior": gnode("prior_L0", "instance", P["prior"], label_dir=DOWN),
        }
        E = {
            "type": gedge(N["product"], N["class"], "type"),
            "gear": gedge(N["product"], N["gear"], "hasPart"),
            "vib": gedge(N["gear"], N["vib"], "vibration"),
            "t1": gedge(N["h1"], N["class"], "type"), "t2": gedge(N["h2"], N["class"]), "t3": gedge(N["h3"], N["class"]),
            "a1": gedge(N["h1"], N["v1"], "vibration"), "a2": gedge(N["h2"], N["v2"]), "a3": gedge(N["h3"], N["v3"]),
            "d1": gedge(N["prior"], N["v1"], "wasDerivedFrom", LIKELIHOOD, 0.95), "d2": gedge(N["prior"], N["v2"], None, LIKELIHOOD, 0.95),
            "d3": gedge(N["prior"], N["v3"], None, LIKELIHOOD, 0.95),
            "b": gedge(N["vib"], N["prior"], "belief", LIKELIHOOD, 0.95),
        }
        place([*N.values(), *E.values()])
        panel = code_panel("SPARQL  ·  history of the same type", height=5.7, center=(4.75, 0.0))
        title = header("Scene 6  ·  A first estimate from history")
        cap = caption("Historic data of this type gives a first estimate: wide, but better than nothing.")

        base = ["class", "product", "gear", "vib"]
        self.add(*[E[k] for k in ("type", "gear", "vib")], *[N[k] for k in base])
        self.play(FadeIn(title), FadeIn(panel), FadeIn(cap), run_time=1.2)

        q = [code_line(panel, "SELECT ?v WHERE {", MUTED),
             code_line(panel, "  ?h a ag:AngleGrinder ;", INSTANCE),
             code_line(panel, "     ag:vibration ?v .", INSTANCE),
             code_line(panel, "}  # n = 40 past instances", MUTED)]
        self.play(AddTextLetterByLetter(q[0], time_per_char=0.03), AddTextLetterByLetter(q[1], time_per_char=0.03),
                  AddTextLetterByLetter(q[2], time_per_char=0.03), AddTextLetterByLetter(q[3], time_per_char=0.03),
                  Indicate(N["class"].core, color=CLASS, scale_factor=1.3), run_time=2.4)
        self.play(LaggedStart(*[
            AnimationGroup(FadeIn(N[h], scale=0.4), GrowArrow(E[t].arrow), *([FadeIn(E[t].pill)] if E[t].pill else []))
            for h, t in (("h1", "t1"), ("h2", "t2"), ("h3", "t3"))], lag_ratio=0.35), run_time=2.2)
        self.play(LaggedStart(*[
            AnimationGroup(FadeIn(N[v], scale=0.4), GrowArrow(E[a].arrow), *([FadeIn(E[a].pill)] if E[a].pill else []))
            for v, a in (("v1", "a1"), ("v2", "a2"), ("v3", "a3"))], lag_ratio=0.35), run_time=2.2)
        self.wait(0.4)

        code_line(panel, "")
        b1 = code_line(panel, "BIND(prob:mix(?v1, ?v2) AS ?p)", LIKELIHOOD)
        b2 = code_line(panel, "# folded over all ?v_i", MUTED)
        anim, r = ripple(N["prior"], LIKELIHOOD)
        self.play(FadeIn(N["prior"], scale=0.4), anim, AddTextLetterByLetter(b1, time_per_char=0.03), AddTextLetterByLetter(b2, time_per_char=0.03), run_time=1.6)
        self.remove(r)
        self.play(*[GrowArrow(E[k].arrow) for k in ("d1", "d2", "d3")], FadeIn(E["d1"].pill), run_time=1.2)
        self.play(flow(E["d1"], LIKELIHOOD, reverse=True), flow(E["d2"], LIKELIHOOD, reverse=True), flow(E["d3"], LIKELIHOOD, reverse=True), run_time=1.6)

        code_line(panel, "")
        i1 = code_line(panel, "INSERT DATA {", MUTED)
        i2 = code_line(panel, "  :prior_L0 prov:wasDerivedFrom", LIKELIHOOD)
        i3 = code_line(panel, "    :v_1 , ... , :v_40 .", LIKELIHOOD)
        i4 = code_line(panel, "  :vibration ag:belief :prior_L0 .", INSTANCE)
        i5 = code_line(panel, "}", MUTED)
        self.play(*[AddTextLetterByLetter(x, time_per_char=0.03) for x in (i1, i2, i3, i4, i5)],
                  GrowArrow(E["b"].arrow), FadeIn(E["b"].pill), run_time=2.2)
        self.play(flow(E["b"], LIKELIHOOD, reverse=True, run_time=1.6))
        self.play(*to_known(N["vib"], 0.75), Flash(N["vib"].core.get_center(), color=INSTANCE, line_length=0.18, flash_radius=0.5), run_time=1.6)
        self.wait(2.5)


class KgOperationsUpdate(Scene):
    def construct(self):
        self.camera.background_color = BACKGROUND
        self.add(backdrop())
        parts = [("Housing", 2.6), ("Gear", 0.9), ("Shaft", -0.8), ("Bearing", -2.5)]
        N = {"prod": gnode("Product B", "instance", (-3.4, 0.0))}
        E = {}
        for name, y in parts:
            N[name] = gnode(name, "instance", (-0.6, y))
            N[name + "_s"] = gnode("installed", "instance", (2.6, y))
            E[name] = gedge(N["prod"], N[name], "hasPart" if name == "Housing" else None)
            E[name + "_s"] = gedge(N[name], N[name + "_s"], "state" if name == "Housing" else None)
        place([*N.values(), *E.values()])
        panel = code_panel("UPDATE  ·  one per operation", height=4.6, center=(4.75, 0.4))
        title = header("Scene 24  ·  Every operation updates the factory")
        cap = caption("Every executed operation updates the knowledge graph.")
        label = Text("KG updates", font=FONT, font_size=18, color=MUTED)
        counter = Integer(0, font_size=34, color=INSTANCE)
        counter.add_updater(lambda m: m.set_value(int(round(tracker.get_value()))))
        tracker = ValueTracker(0)
        stat = VGroup(label, counter).arrange(RIGHT, buff=0.2).move_to(np.array([4.75, -2.5, 0.0]))

        self.add(*E.values(), *N.values())
        self.play(FadeIn(title), FadeIn(panel), FadeIn(cap), FadeIn(stat), run_time=1.2)
        self.wait(0.6)

        ops = [("Housing", "disassemble housing"), ("Gear", "remove gear"), ("Shaft", "pull shaft"), ("Bearing", "extract bearing")]
        for k, (name, desc) in enumerate(ops, start=1):
            y = dict(parts)[name]
            op = recolor(gnode(f"op_{k}", "instance", (5.6 * SCALE + SHIFT[0], y * SCALE), label_dir=DOWN), LIKELIHOOD)
            e_op = gedge(N[name + "_s"], op, "wasGeneratedBy" if k == 1 else None, LIKELIHOOD, 0.95)
            low = name.lower()
            self.play(reset_panel(panel), run_time=0.4)
            l = [code_line(panel, f"# update {k} / 4  ·  {desc}", MUTED),
                 code_line(panel, f"DELETE {{ :{low} ag:state ?s }}", WARM),
                 code_line(panel, f"INSERT {{ :{low} ag:state", INSTANCE),
                 code_line(panel, "         ag:removed ;", INSTANCE),
                 code_line(panel, f"    prov:wasGeneratedBy :op_{k} }}", LIKELIHOOD)]
            self.play(FadeIn(op, scale=0.4), GrowArrow(e_op.arrow), *([FadeIn(e_op.pill)] if e_op.pill else []),
                      *[AddTextLetterByLetter(x, time_per_char=0.02) for x in l], run_time=2.0)
            new_text = Text("removed", font=FONT, font_size=22, color=MUTED).next_to(N[name + "_s"].core, DOWN, buff=0.14)
            self.play(Transform(N[name + "_s"].text, new_text),
                      N[name + "_s"].core.animate.set_fill(MUTED, 0.35).set_stroke(MUTED, 2, 0.8),
                      N[name + "_s"].halo.animate.set_fill(MUTED, 0),
                      N[name].core.animate.set_fill(MUTED, 0.5),
                      N[name].halo.animate.set_fill(MUTED, 0),
                      tracker.animate.set_value(k),
                      Flash(N[name + "_s"].core.get_center(), color=INSTANCE, line_length=0.16, flash_radius=0.45), run_time=1.4)
            self.wait(0.3)
        self.wait(2)


class KgReprocessAndMeasure(Scene):
    def construct(self):
        self.camera.background_color = BACKGROUND
        self.add(backdrop())
        N = {
            "prod": gnode("Product B", "instance", (-3.4, 0.9)),
            "shaft": gnode("Shaft", "instance", (-0.6, 0.9)),
            "hard": gnode("hardness", "instance", (2.4, 2.2), uncertainty=0.12),
            "b2": gnode("belief_L2", "instance", (5.4, 2.2), label_dir=UP),
            "re": gnode("reprocess_3", "instance", (5.4, 0.9), label_dir=DOWN),
            "test": gnode("material_test_8", "instance", (2.4, -2.0), label_dir=DOWN),
            "b3": gnode("belief_L3", "instance", (5.4, -2.0), label_dir=DOWN),
        }
        recolor(N["re"], LIKELIHOOD)
        recolor(N["test"], LIKELIHOOD)
        E = {
            "ps": gedge(N["prod"], N["shaft"], "hasPart"),
            "sh": gedge(N["shaft"], N["hard"], "hardness"),
            "hb2": gedge(N["hard"], N["b2"], "belief"),
            "inv": gedge(N["b2"], N["re"], "wasInvalidatedBy", WARM, 0.95),
            "bt": gedge(N["b3"], N["test"], "wasDerivedFrom", LIKELIHOOD, 0.95),
            "hb3": gedge(N["hard"], N["b3"], "belief", LIKELIHOOD, 0.95),
        }
        place([*N.values(), *E.values()])
        panel = code_panel("INSERT DATA  ·  keep the history", height=4.9, center=(4.75, 0.3))
        title = header("Scenes 25–27  ·  Reprocessing changes the material")
        cap1 = caption("Reprocessing changes the material. The factory does not assume the result.")
        cap2 = caption("Material test: the hardness is known again.")

        self.add(E["ps"], E["sh"], E["hb2"], N["prod"], N["shaft"], N["hard"], N["b2"])
        self.play(FadeIn(title), FadeIn(panel), FadeIn(cap1), run_time=1.2)
        self.wait(0.6)

        a = [code_line(panel, "INSERT DATA {", MUTED),
             code_line(panel, "  :belief_L2", WARM),
             code_line(panel, "    prov:wasInvalidatedBy", WARM),
             code_line(panel, "      :reprocess_3 .", WARM),
             code_line(panel, "}", MUTED),
             code_line(panel, ""),
             code_line(panel, "# :shaft ag:hardness has no", MUTED),
             code_line(panel, "#   current value => unknown", MUTED)]
        anim, r = ripple(N["re"], WARM)
        self.play(FadeIn(N["re"], scale=0.4), anim, *[AddTextLetterByLetter(x, time_per_char=0.025) for x in a[:5]], run_time=1.6)
        self.remove(r)
        self.play(GrowArrow(E["inv"].arrow), FadeIn(E["inv"].pill), run_time=1)
        self.play(flow(E["inv"], WARM, run_time=1.2))
        self.play(*dim(N["b2"]), E["hb2"].animate.set_opacity(0.3),
                  *to_ghost(N["hard"]), *[AddTextLetterByLetter(x, time_per_char=0.025) for x in a[6:]], run_time=2.2)
        self.wait(1.6)

        self.play(reset_panel(panel), FadeOut(cap1), run_time=0.5)
        self.play(FadeIn(cap2), run_time=0.5)
        c = [code_line(panel, "INSERT DATA {", MUTED),
             code_line(panel, "  :material_test_8 a ag:NDT .", LIKELIHOOD),
             code_line(panel, "  :belief_L3", INSTANCE),
             code_line(panel, "    prov:wasDerivedFrom", INSTANCE),
             code_line(panel, "      :material_test_8 .", INSTANCE),
             code_line(panel, "  :hardness ag:belief :belief_L3 .", INSTANCE),
             code_line(panel, "}", MUTED)]
        anim, r = ripple(N["test"], LIKELIHOOD)
        self.play(FadeIn(N["test"], scale=0.4), anim, *[AddTextLetterByLetter(x, time_per_char=0.025) for x in c[:2]], run_time=1.4)
        self.remove(r)
        anim, r = ripple(N["b3"], INSTANCE)
        self.play(FadeIn(N["b3"], scale=0.4), anim, GrowArrow(E["bt"].arrow), FadeIn(E["bt"].pill),
                  *[AddTextLetterByLetter(x, time_per_char=0.025) for x in c[2:5]], run_time=1.6)
        self.remove(r)
        self.play(flow(E["bt"], LIKELIHOOD, reverse=True), GrowArrow(E["hb3"].arrow), FadeIn(E["hb3"].pill),
                  *[AddTextLetterByLetter(x, time_per_char=0.025) for x in c[5:]], run_time=1.6)
        self.play(flow(E["hb3"], LIKELIHOOD, reverse=True, run_time=1.2))
        self.play(*to_known(N["hard"], 0.12), Flash(N["hard"].core.get_center(), color=INSTANCE, line_length=0.18, flash_radius=0.5), run_time=1.6)
        self.wait(2.5)
