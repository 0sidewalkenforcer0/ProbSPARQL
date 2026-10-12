(() => {
  'use strict';
  const uq = 'http://example.org/ontology/uncertainty#';
  const el = id => document.getElementById(`guide${id}`);
  const descriptions = {
    gmmLiteral: 'Gaussian / Gaussian mixture',
    histogramLiteral: 'Histogram',
    dirichletLiteral: 'Dirichlet'
  };
  const constraints = {
    gmmLiteral: { rules: {
      'Source': 'Supplementary Material · § S.3.2 · pp. 6–7',
      'JSON template · full covariance · symbolic placeholders': `{
  "n_components": K,
  "dimensions": d,
  "covariance_type": "full",
  "weights": [w₁, …, wₖ],
  "means": [
    [μ₁₁, …, μ₁d],
    …,
    [μₖ₁, …, μₖd]
  ],
  "covariances": [
    [
      [Σ₁[1,1], …, Σ₁[1,d]],
      …,
      [Σ₁[d,1], …, Σ₁[d,d]]
    ],
    …,
    [
      [Σₖ[1,1], …, Σₖ[1,d]],
      …,
      [Σₖ[d,1], …, Σₖ[d,d]]
    ]
  ]
}`,
      'Dimensions': 'K = n_components ∈ ℕ⁺; d = dimensions ∈ ℕ⁺. weights: K; means: K × d.',
      'Weights': 'wᵢ ≥ 0; Σᵢ wᵢ = 1, up to the configured numerical tolerance.',
      'Covariance type': 'full | diag | spherical',
      'diag / spherical': 'diag: K × d strictly positive variances. spherical: K strictly positive isotropic variances.',
      'full': 'K × d × d; Σᵢ = Σᵢᵀ; Σᵢ ≻ 0 (positive definite).',
      'Support': 'Gaussian mixture density on ℝᵈ. Declared domain restrictions are not applied by datatype interpretation.'
    } },
    histogramLiteral: { rules: {
      'Source': 'Supplementary Material · § S.3.4 · pp. 8–9',
      'Fields': 'dimensions, edges, weights',
      'Dimensions & boundaries': 'd ∈ ℕ⁺; edges contains d strictly increasing boundary arrays B⁽ʲ⁾ = (b₀⁽ʲ⁾, …, bₙⱼ⁽ʲ⁾).',
      'Weight tensor': 'Shape: (n₁, …, n_d); wᵢ₁,…,ᵢd ≥ 0.',
      'Normalization': 'Σ wᵢ₁,…,ᵢd = 1, up to the configured numerical tolerance.',
      'Density': 'Piecewise uniform within each rectangular bin C: f(x) = w_C / volume(C).'
    } },
    dirichletLiteral: { rules: {
      'Source': 'Supplementary Material · § S.3.3 · pp. 7–8',
      'Parameters': 'alphas = (α₁, …, α_K); K ≥ 2; αᵢ > 0.',
      'Concentration': 'α₀ = Σᵢ αᵢ; no requirement that α₀ = 1.',
      'Support': 'Δᴷ⁻¹ = {x ∈ ℝᴷ : xᵢ ≥ 0, Σᵢ xᵢ = 1}.',
      'Expected proportions': 'E[Xᵢ] = αᵢ / α₀. At fixed proportions, α₀ controls concentration around the mean.'
    } }
  };
  let bundled = [], live = [], choices = [], started = false;
  const iri = value => `<${value.replace(/[<>"{}|^`\\\u0000-\u0020]/g, c => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'))}>`;
  function renderNode() {
    const item = choices[Number(el('Node').value)];
    el('Plot').hidden = true;
    for (const id of ['Json', 'Literal', 'Rdf', 'Source']) el(id).textContent = '';
    if (!item) { el('Json').textContent = 'No examples available for this distribution.'; return; }
    el('Source').textContent = `Source: ${item.source}`;
    try {
      const data = JSON.parse(item.value);
      el('Json').textContent = JSON.stringify(data, null, 2);
      showDistribution({ type: 'literal', datatype: uq + item.datatype, value: item.value },
        item.node.split(/[/#]/).pop(), null, {
          pane: el('Plot'), svg: el('PlotSvg'), summary: el('PlotSummary'),
          selector: el('PlotDimension'), title: el('PlotTitle')
        });
    } catch (error) {
      el('Json').textContent = item.value;
      el('Plot').hidden = false;
      el('PlotSvg').replaceChildren();
      el('PlotSummary').textContent = `Cannot display this distribution: ${error.message}`;
    }
    const literal = `${JSON.stringify(item.value)}^^uq:${item.datatype}`;
    el('Literal').textContent = literal;
    const subject = item.blank ? '_:exampleVariable' : iri(item.node);
    el('Rdf').textContent = `@prefix uq: <${uq}> .\n\n`
      + (item.measurement ? `${iri(item.measurement)} uq:representedBy ${subject} .\n\n` : '')
      + `${subject}\n`
      + (item.domain ? `    uq:hasDomain ${iri(item.domain)} ;\n` : '')
      + `    uq:hasDistribution ${literal} .`;
  }
  function render() {
    const type = el('Type').value, name = descriptions[type];
    el('Description').textContent = name;
    el('Datatype').textContent = uq + type;
    el('Constraints').replaceChildren();
    for (const [title, rule] of Object.entries(constraints[type].rules)) {
      const dt = document.createElement('dt'), dd = document.createElement('dd');
      dt.textContent = title;
      if (title.startsWith('JSON template')) {
        const pre = document.createElement('pre'), code = document.createElement('code');
        code.textContent = rule; pre.append(code); dd.append(pre);
      } else {
        dd.textContent = rule;
      }
      el('Constraints').append(dt, dd);
    }
    const classes = {
      gmmLiteral: ['GMMDatatype', 'GMMValue'],
      histogramLiteral: ['HistogramDatatype', 'HistogramValue'],
      dirichletLiteral: ['DirichletDatatype', 'DirichletValue']
    };
    const [parser, value] = classes[type];
    el('JenaSteps').replaceChildren();
    const steps = [
      ['1. Define the datatype', `uq:${type} → ${parser}: JSON syntax, validation, and ${value}.`],
      ['2. Register at engine startup', `TypeMapper.getInstance().registerDatatype(${parser}.INSTANCE)`],
      ['3. Resolve the typed literal', `"{…}"^^uq:${type} → Jena looks up the datatype IRI in TypeMapper.`],
      ['4. Interpret the JSON value', `${parser}.parse(JSON) → ${value}. Invalid lexical forms fail datatype interpretation; parsed values may be cached.`],
      ['5. Bind in SPARQL', '?dist binds the RDF literal. Expressions access it through NodeValue.'],
      ['6. Resolve a registered function', 'FunctionRegistry maps function IRIs to implementations, e.g. prob:mean → Mean; prob:std → Std; prob:pdf → PDF; prob:cdf → CDF; prob:jsd → PolyJSD; prob:scale → Scale; prob:sample → Sample. Supported datatypes depend on the function. Datatypes and functions are registered separately.'],
      ['7. Read and compute', `NodeValue.asNode().getLiteralValue() → ${value} → datatype-specific computation → result.`]
    ];
    steps.forEach(([label, detail]) => {
      const li = document.createElement('li');
      const title = document.createElement('strong'), caption = document.createElement('span');
      title.textContent = label; caption.textContent = detail;
      li.append(title, caption); el('JenaSteps').append(li);
    });
    choices = [...live, ...bundled].filter(item => item.datatype === type);
    el('Node').replaceChildren();
    choices.forEach((item, index) => {
      const option = document.createElement('option'); option.value = index;
      option.textContent = `${item.source === 'Loaded dataset' ? 'Loaded' : 'Bundled'} · ${item.node.split(/[/#]/).pop()}`;
      if (item.datatype === 'gmmLiteral') {
        try {
          const data = JSON.parse(item.value);
          option.textContent += ` · ${data.dimensions}D · ${data.covariance_type}`;
        } catch { /* Preserve the label for malformed literals. */ }
      }
      el('Node').append(option);
    });
    renderNode();
  }
  async function refresh() {
    el('Refresh').disabled = true;
    el('Status').textContent = 'Loading distribution data nodes…';
    try {
      const response = await fetch('distribution-examples.json', { cache: 'no-store' });
      if (!response.ok) throw Error(`Examples: HTTP ${response.status}`);
      bundled = await response.json();
      render();
    } catch (error) {
      el('Status').textContent = `Bundled examples unavailable: ${error.message}`;
    }
    try {
      const query = `SELECT DISTINCT ?node ?distribution WHERE {
        { ?node ?predicate ?distribution } UNION { GRAPH ?graph { ?node ?predicate ?distribution } }
        FILTER(isLiteral(?distribution))
        FILTER(DATATYPE(?distribution) IN (${Object.keys(descriptions).map(t => iri(uq + t)).join(', ')}))
      } ORDER BY ?node`;
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 15000);
      let result;
      try { result = await sparql(query, controller.signal); }
      finally { clearTimeout(timeout); }
      live = result.results.bindings.map(row => ({node: row.node.value, blank: row.node.type === 'bnode', datatype: row.distribution.datatype.slice(uq.length), value: row.distribution.value, source: 'Loaded dataset'}));
      el('Status').textContent = '';
    } catch (error) {
      live = [];
      el('Status').textContent = `Could not read the loaded dataset. Showing ${bundled.length} bundled examples. ${error.message}`;
    } finally {
      render(); el('Refresh').disabled = false;
    }
  }
  el('Type').addEventListener('change', render);
  el('Node').addEventListener('change', renderNode);
  el('Refresh').addEventListener('click', refresh);
  document.querySelector('[data-tab="distributions"]').addEventListener('click', () => {
    if (!started) { started = true; refresh(); }
  });
  render();
})();
