/* Distribution previews use numeric SVG coordinates; dataset text stays textContent. */
function isDistribution(term) {
  return term && term.type === 'literal' && /#(gmmLiteral|histLiteral|dirichletLiteral)$/.test(term.datatype || '');
}

function distributionSeries(term, dimension = 0) {
  const data = JSON.parse(term.value);
  const numeric = values => Array.isArray(values) && values.length > 0 && values.every(Number.isFinite);
  const normalize = values => {
    if (!numeric(values) || values.some(v => v < 0)) throw Error('Invalid distribution weights.');
    const sum = values.reduce((a, b) => a + b, 0);
    if (!(sum > 0) || !Number.isFinite(sum)) throw Error('Invalid total weight.');
    return values.map(v => v / sum);
  };
  if (term.datatype.endsWith('#gmmLiteral')) {
    const weights = normalize(data.weights);
    const dimensions = data.dimensions || (Array.isArray(data.means?.[0]) ? data.means[0].length : 1);
    if (!Number.isInteger(dimensions) || dimensions < 1 || dimension >= dimensions) throw Error('Invalid dimensions.');
    const means = weights.map((_, k) => Array.isArray(data.means?.[k]) ? data.means[k][dimension] : data.means?.[k]);
    const variances = weights.map((_, k) => {
      const cov = data.covariance_type === 'tied' ? data.covariances : data.covariances?.[k];
      return Array.isArray(cov?.[dimension]) ? cov[dimension][dimension] : Array.isArray(cov) ? cov[dimension] : cov;
    });
    if (!numeric(means) || !numeric(variances) || variances.some(v => v <= 0)) throw Error('Invalid GMM means or variances.');
    const lo = Math.min(...means.map((m, k) => m - 4 * Math.sqrt(variances[k])));
    const hi = Math.max(...means.map((m, k) => m + 4 * Math.sqrt(variances[k])));
    // Add samples around each component so narrow peaks remain visible.
    const xs = new Set(Array.from({length: 401}, (_, i) => lo + (hi - lo) * i / 400));
    means.forEach((m, k) => {
      for (let i = 0; i <= 100; i++) xs.add(m + (i / 100 * 8 - 4) * Math.sqrt(variances[k]));
    });
    const points = [...xs].sort((a, b) => a - b).map(x => [x, weights.reduce((sum, w, k) => sum + w * Math.exp(-((x - means[k]) ** 2) / (2 * variances[k])) / Math.sqrt(2 * Math.PI * variances[k]), 0)]);
    return { points, dimensions, label: dimensions > 1 ? `GMM — marginal density, dimension ${dimension + 1}` : 'GMM — probability density', yLabel: 'Density', summary: weights.map((w, k) => `C${k + 1}: weight=${w.toPrecision(4)}, mean=${means[k].toPrecision(5)}, σ=${Math.sqrt(variances[k]).toPrecision(4)}`).join('\n') };
  }
  if (term.datatype.endsWith('#histLiteral')) {
    if ((data.dimensions || 1) !== 1) throw Error('Histogram preview currently supports one dimension only.');
    const edges = data.bins || (Array.isArray(data.edges?.[0]) ? data.edges[0] : data.edges);
    const weights = normalize(data.weights);
    if (!numeric(edges) || edges.length !== weights.length + 1 || edges.some((v, i) => i > 0 && v <= edges[i - 1])) throw Error('Invalid histogram edges.');
    const bars = weights.map((w, i) => [edges[i], edges[i + 1], w / (edges[i + 1] - edges[i])]);
    return { bars, dimensions: 1, label: 'Histogram — probability density', yLabel: 'Density', summary: `${weights.length} bins; bar area represents probability.` };
  }
  if (term.datatype.endsWith('#dirichletLiteral')) {
    if (!numeric(data.alphas) || data.alphas.length < 2 || data.alphas.some(a => a <= 0)) throw Error('Invalid Dirichlet parameters.');
    const weights = normalize(data.alphas);
    return { bars: weights.map((w, i) => [i + 0.65, i + 1.35, w]), dimensions: 1, label: 'Dirichlet — expected category probabilities', yLabel: 'E[p]', summary: 'Category means αᵢ / Σα; this is not the joint probability density.' };
  }
  throw Error('Unsupported distribution datatype.');
}

// Evaluate the joint density, including cross-covariance, for a two-dimensional GMM.
function distributionSurface(term) {
  const data = JSON.parse(term.value);
  const total = data.weights?.reduce((a, b) => a + b, 0);
  if (!Number.isFinite(total) || total <= 0) throw Error('Invalid total weight.');
  const components = data.weights.map((weight, k) => {
    const mean = data.means?.[k];
    const cov = data.covariance_type === 'tied' ? data.covariances : data.covariances?.[k];
    const matrix = Array.isArray(cov?.[0]);
    const a = matrix ? cov[0][0] : Array.isArray(cov) ? cov[0] : cov;
    const b = matrix ? cov[0][1] : 0;
    const c = matrix ? cov[1]?.[1] : Array.isArray(cov) ? cov[1] : cov;
    const det = a * c - b * b;
    if (!Array.isArray(mean) || mean.length !== 2 || ![...mean, weight, a, b, c, det].every(Number.isFinite)
        || weight < 0 || a <= 0 || c <= 0 || det <= 0
        || (matrix && Math.abs(b - cov[1]?.[0]) > 1e-10)) throw Error('Invalid two-dimensional GMM covariance or mean.');
    return { mean, weight: weight / total, a, b, c, det };
  });
  const bounds = [0, 1].map(d => [
    Math.min(...components.map(v => v.mean[d] - 4 * Math.sqrt(d ? v.c : v.a))),
    Math.max(...components.map(v => v.mean[d] + 4 * Math.sqrt(d ? v.c : v.a)))
  ]);
  const density = (x, y) => components.reduce((sum, v) => {
    const dx = x - v.mean[0], dy = y - v.mean[1];
    return sum + v.weight * Math.exp(-(v.c * dx * dx - 2 * v.b * dx * dy + v.a * dy * dy) / (2 * v.det)) / (2 * Math.PI * Math.sqrt(v.det));
  }, 0);
  return { components, bounds, density };
}

function drawDistributionSurface(svg, surface) {
  const { bounds, density } = surface;
  const n = 55;
  const value = (d, t) => bounds[d][0] + t * (bounds[d][1] - bounds[d][0]);
  const grid = Array.from({length: n + 1}, (_, i) => Array.from({length: n + 1}, (_, j) => density(value(0, i / n), value(1, j / n))));
  const top = Math.max(...grid.flat()) * 1.1;
  if (!Number.isFinite(top) || top <= 0 || !bounds.flat().every(Number.isFinite)) throw Error('Distribution cannot be plotted at this numeric scale.');
  svg.setAttribute('viewBox', '0 0 700 440');
  const project = (x, y, z) => [100 + x * 420 + y * 140, 330 + x * 45 - y * 110 - z / top * 235];
  const add = (tag, attrs, text) => {
    const node = document.createElementNS(svg.namespaceURI, tag);
    Object.entries(attrs).forEach(([k, v]) => node.setAttribute(k, v));
    if (text !== undefined) node.textContent = text;
    svg.appendChild(node);
    return node;
  };
  const line = (a, b, stroke = '#64748b') => add('line', {x1: a[0], y1: a[1], x2: b[0], y2: b[1], stroke});
  const text = (p, content) => add('text', {x: p[0], y: p[1], fill: '#cbd5e1', 'font-size': 12, 'text-anchor': 'middle'}, content);
  const fmt = v => Number(v.toPrecision(3)).toString();
  for (let i = 0; i <= 4; i++) {
    const t = i / 4;
    line(project(t, 0, 0), project(t, 1, 0), '#253144');
    line(project(0, t, 0), project(1, t, 0), '#253144');
    const px = project(t, 0, 0), py = project(1, t, 0);
    text([px[0], px[1] + 20], fmt(value(0, t)));
    text([py[0] + 24, py[1] + 5], fmt(value(1, t)));
    const pz = project(0, 0, t * top);
    text([pz[0] - 35, pz[1] + 4], fmt(t * top));
  }
  // Back-to-front painting keeps nearer mesh cells in front of farther cells.
  for (let j = n - 1; j >= 0; j--) {
    for (let i = 0; i < n; i++) {
      const corners = [[i, j], [i + 1, j], [i + 1, j + 1], [i, j + 1]];
      const z = corners.reduce((sum, [a, b]) => sum + grid[a][b], 0) / 4;
      const cell = add('polygon', {
        points: corners.map(([a, b]) => project(a / n, b / n, grid[a][b]).join(',')).join(' '),
        fill: `hsl(${240 - 240 * Math.min(1, z / (top / 1.1))}, 85%, 50%)`,
        stroke: '#0b1320', 'stroke-opacity': 0.4, 'stroke-width': 0.45
      });
      const tooltip = document.createElementNS(svg.namespaceURI, 'title');
      tooltip.textContent = `X₁=${fmt(value(0, (i + 0.5) / n))}; X₂=${fmt(value(1, (j + 0.5) / n))}; density≈${fmt(z)}`;
      cell.appendChild(tooltip);
    }
  }
  line(project(0, 0, 0), project(1, 0, 0));
  line(project(1, 0, 0), project(1, 1, 0));
  line(project(0, 0, 0), project(0, 0, top));
  text([310, 418], 'X₁');
  text([630, 350], 'X₂');
  text([95, 65], 'Density');
}

// Lanczos approximation for positive concentration parameters.
function distributionLogGamma(z) {
  const c = [676.5203681218851, -1259.1392167224028, 771.3234287776531,
    -176.6150291621406, 12.507343278686905, -0.13857109526572012,
    9.984369578019572e-6, 1.5056327351493116e-7];
  if (z < 0.5) return Math.log(Math.PI) - Math.log(Math.sin(Math.PI * z)) - distributionLogGamma(1 - z);
  z -= 1;
  let x = 0.9999999999998099;
  c.forEach((v, i) => { x += v / (z + i + 1); });
  const t = z + 7.5;
  return 0.5 * Math.log(2 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(x);
}

function dirichletMarginals(alphas) {
  return alphas.map((a, index) => {
    const b = alphas.reduce((sum, v, i) => sum + (i === index ? 0 : v), 0);
    const logBeta = distributionLogGamma(a) + distributionLogGamma(b) - distributionLogGamma(a + b);
    // Do not evaluate singular endpoints when a or b is below one.
    const xs = new Set(Array.from({length: 401}, (_, i) => 0.001 + 0.998 * i / 400));
    if (a > 1 && b > 1) xs.add((a - 1) / (a + b - 2));
    const points = [...xs].sort((x, y) => x - y).map(x => [x,
      Math.exp((a - 1) * Math.log(x) + (b - 1) * Math.log1p(-x) - logBeta)]);
    if (!points.every(p => p.every(Number.isFinite))) throw Error('Beta density cannot be plotted at this numeric scale.');
    return {a, b, points};
  });
}

function appendDirichletMarginals(svg, alphas) {
  const marginals = dirichletMarginals(alphas);
  const colors = ['#00c9ad', '#38bdf8', '#a78bfa', '#fbbf24', '#fb7185'];
  const add = (tag, attrs, text) => {
    const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
    Object.entries(attrs).forEach(([k, v]) => node.setAttribute(k, v));
    if (text !== undefined) node.textContent = text;
    svg.appendChild(node);
    return node;
  };
  svg.setAttribute('viewBox', `0 0 700 ${320 + marginals.length * 190}`);
  add('text', {x: 65, y: 315, fill: '#cbd5e1', 'font-size': 14}, 'Beta marginal densities — category proportions');
  marginals.forEach(({a, b, points}, i) => {
    const base = 350 + i * 190;
    const top = Math.max(...points.map(p => p[1])) * 1.1;
    const x = v => 65 + v * 600;
    const y = v => base + 120 - v / top * 105;
    const color = colors[i % colors.length];
    add('text', {x: 65, y: base, fill: color, 'font-size': 12}, `Category ${i + 1}: Beta(${a.toPrecision(4)}, ${b.toPrecision(4)})`);
    for (let j = 0; j <= 4; j++) {
      const v = top * j / 4;
      add('line', {x1: 65, x2: 665, y1: y(v), y2: y(v), stroke: '#253144'});
      add('text', {x: 57, y: y(v) + 4, 'text-anchor': 'end', fill: '#94a3b8', 'font-size': 10}, Number(v.toPrecision(3)));
      add('text', {x: x(j / 4), y: base + 138, 'text-anchor': 'middle', fill: '#94a3b8', 'font-size': 10}, j / 4);
    }
    const curve = add('polyline', {points: points.map(p => `${x(p[0])},${y(p[1])}`).join(' '), fill: 'none', stroke: color, 'stroke-width': 2});
    const tooltip = document.createElementNS(svg.namespaceURI, 'title');
    tooltip.textContent = `Category ${i + 1}: proportion on x-axis, probability density on y-axis. Each panel has its own density scale.`;
    curve.appendChild(tooltip);
    add('text', {x: 365, y: base + 157, 'text-anchor': 'middle', fill: '#94a3b8', 'font-size': 11}, 'Proportion p (0–1)');
  });
}

function showDistribution(term, label, dimension = null, target = null) {
  const pane = target?.pane || document.getElementById('distributionPane');
  const svg = target?.svg || document.getElementById('distributionSvg');
  const summary = target?.summary || document.getElementById('distributionSummary');
  const selector = target?.selector || document.getElementById('distributionDimension');
  const title = target?.title || document.getElementById('distributionTitle');
  pane.hidden = false;
  svg.replaceChildren();
  svg.setAttribute("viewBox", "0 0 700 300");
  selector.replaceChildren();
  selector.hidden = true;
  title.textContent = label;
  try {
    const series = distributionSeries(term, dimension === null || dimension === -1 ? 0 : dimension);
    const hasSurface = term.datatype.endsWith("#gmmLiteral") && series.dimensions === 2;
    if (dimension === null) dimension = hasSurface ? -1 : 0;
    title.textContent = `${label} · ${series.label}`;
    summary.textContent = series.summary;
    selector.hidden = series.dimensions <= 1;
    if (hasSurface) {
      const option = document.createElement("option");
      option.value = -1;
      option.textContent = "3D joint density — X₁ × X₂";
      selector.appendChild(option);
    }
    for (let i = 0; i < series.dimensions; i++) {
      const option = document.createElement('option');
      option.value = i;
      option.textContent = `Dimension ${i + 1}`;
      selector.appendChild(option);
    }
    selector.value = dimension;
    selector.onchange = () => showDistribution(term, label, Number(selector.value), target);
    if (hasSurface && dimension === -1) {
      const surface = distributionSurface(term);
      title.textContent = `${label} · GMM — 3D joint probability density`;
      summary.textContent = surface.components.map((v, k) => `C${k + 1}: weight=${v.weight.toPrecision(4)}\nmean=[${v.mean.join(', ')}]\ncovariance=[[${v.a}, ${v.b}], [${v.b}, ${v.c}]]`).join('\n\n') + '\n\nHeight and color show joint density. Hover over the mesh for values. Surface uses a sampled grid.';
      drawDistributionSurface(svg, surface);
      return;
    }
    const xs = series.points ? series.points.map(p => p[0]) : series.bars.flatMap(b => b.slice(0, 2));
    const ys = series.points ? series.points.map(p => p[1]) : series.bars.map(b => b[2]);
    const lo = Math.min(...xs), hi = Math.max(...xs), top = Math.max(...ys) * 1.1;
    if (![lo, hi, top].every(Number.isFinite) || !(hi > lo) || !(top > 0)) throw Error('Distribution cannot be plotted at this numeric scale.');
    const x = v => 65 + (v - lo) / (hi - lo) * 600;
    const y = v => 255 - v / top * 215;
    const add = (tag, attrs, text) => {
      const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
      Object.entries(attrs).forEach(([key, value]) => node.setAttribute(key, value));
      if (text !== undefined) node.textContent = text;
      svg.appendChild(node);
      return node;
    };
    const format = v => Number(v.toPrecision(4)).toString();
    for (let i = 0; i <= 4; i++) {
      const v = top * i / 4;
      add('line', {x1: 65, x2: 665, y1: y(v), y2: y(v), stroke: '#253144'});
      add('text', {x: 57, y: y(v) + 4, 'text-anchor': 'end', fill: '#94a3b8', 'font-size': 11}, format(v));
      const tick = lo + (hi - lo) * i / 4;
      add('text', {x: x(tick), y: 277, 'text-anchor': 'middle', fill: '#94a3b8', 'font-size': 11}, format(tick));
    }
    add('text', {x: 65, y: 20, fill: '#94a3b8', 'font-size': 12}, series.yLabel);
    if (series.points) {
      add('polyline', {points: series.points.map(p => `${x(p[0])},${y(p[1])}`).join(' '), fill: 'none', stroke: '#edb70e', 'stroke-width': 2});
      series.points.forEach(p => {
        const point = add('circle', {cx: x(p[0]), cy: y(p[1]), r: 4, fill: 'transparent'});
        const title = document.createElementNS(svg.namespaceURI, 'title');
        title.textContent = `x=${format(p[0])}; density=${format(p[1])}`;
        point.appendChild(title);
      });
    } else series.bars.forEach(b => add('rect', {x: x(b[0]), y: y(b[2]), width: x(b[1]) - x(b[0]), height: 255 - y(b[2]), fill: '#edb70e', opacity: 0.8}));
    if (term.datatype.endsWith('#dirichletLiteral')) {
      appendDirichletMarginals(svg, JSON.parse(term.value).alphas);
      summary.textContent = 'Bars show expected category proportions. Curves show Beta marginal densities for each proportion. Proportions sum to 1 and are dependent. Each panel uses its own density scale; endpoints are omitted.';
    }
  } catch (error) {
    svg.replaceChildren();
    summary.textContent = `Cannot preview this distribution: ${error.message}`;
  }
}
