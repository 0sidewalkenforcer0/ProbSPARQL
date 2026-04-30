# ProbSPARQL Real Measurement Data Artifact

This folder contains a Turtle conversion of two real manufacturing measurement spreadsheets and a set of SPARQL/ProbSPARQL queries aligned with the modelling pattern used in the paper.

## Files

- `real_measurement_data.ttl`  
  RDF/Turtle data following the paper pattern:
  `sample -> measurable characteristic -> measurement -> uq:representedBy -> uq:RandomVariable -> uq:hasDistribution`.
- `queries/R1_real_rms_threshold.rq`  
  Probabilistic threshold query over real RMS measurements.
- `queries/R2_real_replicate_divergence.rq`  
  Distribution-comparison query between replicate measurements under the same process condition.
- `queries/R4_real_rms_compatible_pairing_divjoin.rq`  
  Prototype `DIVJOIN` query for compatible RMS distribution pairing.
- `queries/Histogram_real_c02_sa_cdf.rq`  
  CDF query over a non-parametric histogram literal derived from real C02 roughness values.
- `queries/Pattern_real_random_variable_retrieval.rq`  
  Minimal retrieval query showing the ontology pattern.
- `real_data_summary.csv`  
  Compact table of the hardness/RMS/RS dataset with computed `P(RMS > 700)` values.
- `../examples/ontologies/uncertainty-datatypes.xsd`
  Shared XML Schema definition for uncertainty datatypes used across the project,
  including `uq:nonNegativeDouble` (as an `xs:double` with minimum value 0.0).

## Source spreadsheets

1. `Data_Hardness_Temp_RMS_reduced.xlsx`
   - 40 measurement rows.
   - Process descriptors: `CC`, `Temp.`
   - Deterministic value: `Hardness`
   - Distribution-valued measurements:
     - `RMS` with `Stand RMS`
     - `RS` with `Stand RS`
   - Encoding:
     - each RMS value is encoded as a one-dimensional one-component GMM literal with mean=`RMS` and variance=`Stand RMS^2`;
     - each RS value is encoded as a one-dimensional one-component GMM literal with mean=`RS` and variance=`Stand RS^2`.

2. `Data C02.xlsx`
   - `Roughness Values` sheet: 17 samples.
   - Process descriptors: laser power, processing speed, and line energy.
   - Deterministic roughness/value measurements included in the TTL:
     - relative density, Sa, Sq, Ra, Rq, Rz.
   - Non-parametric dataset-level distributions:
     - empirical histograms for Sa, Rq, and relative density.

## Mapping decisions

The RDF mapping keeps the same structural pattern as the paper's circular manufacturing vocabulary:

```text
sample/component
  -> measurable characteristic
  -> measurement
  -> uq:representedBy
  -> uq:RandomVariable
  -> uq:hasDomain / uq:hasDistribution
```

For distribution-valued measurements, the measurement node keeps the scalar mean via `om:hasValue`, the empirical standard deviation via `ex:standardDeviation`, and the random-variable representation via `uq:representedBy`.

The current TTL uses provisional example IRIs:

```text
https://w3id.org/probsparql/examples/real-manufacturing/
http://example.org/ontology/uncertainty#
```

Replace these with the final registered vocabulary IRIs before camera-ready publication.

## Real-data query example

`queries/R1_real_rms_threshold.rq` retrieves samples for which:

```text
P(RMS > 700) >= 0.9
```

Using the Gaussian encoding above, this query is expected to return 6 measurement rows in the current data conversion.

## Triple count

The generated Turtle file contains 2386 RDF triples when parsed with RDFLib.

