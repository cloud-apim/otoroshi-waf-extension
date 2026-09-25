import { useEffect, useRef, useState } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import COUNTRIES from '../assets/countries.json';

/**
 * A choropleth of the world, one value per country (ISO 3166 alpha-2).
 *
 * Loaded lazily: maplibre is most of the weight of this chunk, and only the geography tab pays it.
 * There are no tiles and no glyphs, so nothing is fetched from anywhere — the shapes are Natural
 * Earth (public domain), `areas` at 1:110m, and `points` the label point of every country too small
 * to have a shape at that scale (Singapore, Hong Kong, Malta…), drawn as a dot when it has a value.
 *
 * maplibre cannot read css variables, so the palette is resolved from the theme tokens and resolved
 * again whenever `data-theme` changes.
 */

const HIDDEN = ['AQ']; // a continent's worth of pixels that no caller comes from

function resolve(name, host) {
  const probe = document.createElement('span');
  probe.style.color = `var(${name})`;
  probe.style.display = 'none';
  host.appendChild(probe);
  const color = getComputedStyle(probe).color;
  probe.remove();
  const m = color.match(/[\d.]+/g) || [0, 0, 0];
  return { r: +m[0], g: +m[1], b: +m[2], a: m[3] === undefined ? 1 : +m[3] };
}

function mix(a, b, t) {
  const c = (x, y) => Math.round(x + (y - x) * t);
  return `rgb(${c(a.r, b.r)}, ${c(a.g, b.g)}, ${c(a.b, b.b)})`;
}

function paletteOf(host) {
  const surface = resolve('--surface', host);
  const ink = resolve('--ink', host);
  const heat = resolve('--negative', host);
  return {
    // the page behind the map, and the sea on it: the same in flat, the edge of the sphere on a globe
    space: mix(surface, surface, 0),
    sea: mix(surface, ink, 0.035),
    land: mix(surface, ink, 0.1),
    border: mix(surface, ink, 0.22),
    outline: mix(surface, ink, 0.9),
    // from a tint that is still clearly "something" to the full token
    ramp: (t) => mix(surface, heat, 0.18 + 0.82 * t),
  };
}

export const STEPS = 6;

// `speed` is in turns a minute: at 1, slow enough to read a country as it passes
const DEGREES_PER_SECOND = 6;

/**
 * The step of every value, 0 … STEPS-1.
 *
 * Counts are ranked (quantiles): decisions are heavy tailed, and on any proportional scale — log
 * included — the top handful of countries all land on the last step and cannot be told apart. A
 * score is bounded and means something in absolute terms, so it stays linear.
 */
export function stepsOf(values, scale) {
  const entries = [...values.entries()].filter(([, v]) => v > 0);
  const steps = new Map();
  if (scale === 'linear') {
    const max = Math.max(1, ...entries.map(([, v]) => v));
    entries.forEach(([iso, v]) => steps.set(iso, Math.min(STEPS - 1, Math.floor((v / max) * STEPS))));
    return steps;
  }
  const distinct = [...new Set(entries.map(([, v]) => v))].sort((a, b) => a - b);
  const span = Math.max(1, distinct.length - 1);
  entries.forEach(([iso, v]) => {
    // the largest value is always the darkest, the smallest the lightest, whatever their number
    const rank = distinct.length === 1 ? 1 : distinct.indexOf(v) / span;
    steps.set(iso, Math.round(rank * (STEPS - 1)));
  });
  return steps;
}

function fillExpression(values, scale, palette) {
  const pairs = [];
  stepsOf(values, scale).forEach((step, iso) => pairs.push(iso, palette.ramp(step / (STEPS - 1))));
  return pairs.length ? ['match', ['get', 'iso'], ...pairs, palette.land] : palette.land;
}

// the inhabited world, from Alaska to New Zealand; Antarctica is not drawn. In mercator this box is
// about 2.15 times wider than tall, which is the aspect of `.worldmap`: maplibre will not zoom out
// past the width of one world, so a flatter box would crop the poles and a taller one would letterbox.
const WORLD = [
  [-168, -48],
  [180, 72],
];

const SEA = {
  type: 'Feature',
  properties: {},
  geometry: {
    type: 'Polygon',
    coordinates: [
      [
        [-180, -85],
        [180, -85],
        [180, 85],
        [-180, 85],
        [-180, -85],
      ],
    ],
  },
};

/** The flat map shows the whole world; the globe fills the height with the side facing Europe. */
function frame(map, projection) {
  if (projection === 'globe') {
    // a globe at zoom z is about 512 * 2^z / π pixels across
    const height = map.getContainer().clientHeight || 400;
    map.jumpTo({ center: [15, 30], zoom: Math.log2((height * 0.9 * Math.PI) / 512) });
  } else {
    map.fitBounds(WORLD, { padding: 12, animate: false });
  }
}

export default function WorldMap({ values, scale = 'rank', format = String, label, selected, onSelect, projection = 'mercator', spin = false, speed = 1 }) {
  const container = useRef(null);
  const mapRef = useRef(null);
  const latest = useRef({});
  const [palette, setPalette] = useState(null);
  const [ready, setReady] = useState(false);
  const [hover, setHover] = useState(null);

  const max = Math.max(0, ...values.values());
  latest.current = { values, onSelect, projection, speed };

  // theme
  useEffect(() => {
    const host = container.current;
    setPalette(paletteOf(host));
    const observer = new MutationObserver(() => setPalette(paletteOf(host)));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    return () => observer.disconnect();
  }, []);

  // the map itself, once
  useEffect(() => {
    if (!palette || mapRef.current) return;
    const notHidden = ['!', ['in', ['get', 'iso'], ['literal', HIDDEN]]];
    const map = new maplibregl.Map({
      container: container.current,
      style: {
        version: 8,
        sources: {
          areas: { type: 'geojson', data: COUNTRIES.areas, generateId: true },
          points: { type: 'geojson', data: COUNTRIES.points, generateId: true },
          sea: { type: 'geojson', data: SEA },
        },
        layers: [
          { id: 'space', type: 'background', paint: { 'background-color': palette.space } },
          { id: 'sea', type: 'fill', source: 'sea', paint: { 'fill-color': palette.sea } },
          { id: 'areas', type: 'fill', source: 'areas', filter: notHidden, paint: { 'fill-color': palette.land } },
          { id: 'borders', type: 'line', source: 'areas', filter: notHidden, paint: { 'line-color': palette.border, 'line-width': 0.5 } },
          {
            id: 'outline',
            type: 'line',
            source: 'areas',
            filter: notHidden,
            paint: { 'line-color': palette.outline, 'line-width': ['case', ['boolean', ['feature-state', 'hover'], false], 1.4, 0] },
          },
          {
            id: 'points',
            type: 'circle',
            source: 'points',
            filter: ['in', ['get', 'iso'], ['literal', []]],
            paint: {
              'circle-radius': 4.5,
              'circle-color': palette.land,
              'circle-stroke-width': ['case', ['boolean', ['feature-state', 'hover'], false], 1.6, 0.8],
              'circle-stroke-color': palette.outline,
            },
          },
        ],
      },
      bounds: WORLD,
      minZoom: 0.4,
      maxZoom: 6,
      renderWorldCopies: false,
      attributionControl: false,
      dragRotate: false,
      pitchWithRotate: false,
    });
    map.touchZoomRotate.disableRotation();
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');

    let hovered = null;
    const setHovered = (next) => {
      if (hovered) map.setFeatureState(hovered, { hover: false });
      hovered = next;
      if (hovered) map.setFeatureState(hovered, { hover: true });
    };
    const onMove = (e) => {
      const f = map.queryRenderedFeatures(e.point, { layers: ['points', 'areas'] })[0];
      const iso = f && f.properties.iso;
      if (!iso) {
        setHovered(null);
        setHover(null);
        map.getCanvas().style.cursor = '';
        return;
      }
      setHovered({ source: f.source, id: f.id });
      setHover({ iso, x: e.point.x, y: e.point.y });
      map.getCanvas().style.cursor = latest.current.values.has(iso) && latest.current.onSelect ? 'pointer' : '';
    };
    map.on('mousemove', onMove);
    map.on('mouseout', () => {
      setHovered(null);
      setHover(null);
    });
    map.on('click', (e) => {
      const f = map.queryRenderedFeatures(e.point, { layers: ['points', 'areas'] })[0];
      const iso = f && f.properties.iso;
      const { values: v, onSelect: pick } = latest.current;
      if (pick) pick(iso && v.has(iso) ? iso : null);
    });
    map.on('load', () => setReady(true));
    // keep the whole world in view as the card is resized, rather than a crop of it
    const fit = () => frame(map, latest.current.projection);
    const observer = new ResizeObserver(() => {
      map.resize();
      fit();
    });
    observer.observe(container.current);
    mapRef.current = map;
    return () => {
      observer.disconnect();
      map.remove();
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [palette !== null]);

  // data and theme
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !ready || !palette) return;
    const fill = fillExpression(values, scale, palette);
    map.setPaintProperty('space', 'background-color', palette.space);
    map.setPaintProperty('sea', 'fill-color', palette.sea);
    map.setPaintProperty('areas', 'fill-color', fill);
    map.setPaintProperty('borders', 'line-color', palette.border);
    map.setPaintProperty('outline', 'line-color', palette.outline);
    map.setPaintProperty('points', 'circle-color', fill);
    map.setPaintProperty('points', 'circle-stroke-color', palette.outline);
    map.setFilter('points', ['in', ['get', 'iso'], ['literal', [...values.keys()]]]);
    // the selection keeps its outline while the pointer is elsewhere
    const selectedWidth = selected ? ['==', ['get', 'iso'], selected] : false;
    map.setPaintProperty('outline', 'line-width', [
      'case',
      ['any', ['boolean', ['feature-state', 'hover'], false], selectedWidth],
      1.4,
      0,
    ]);
  }, [values, scale, palette, ready, selected]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !ready) return;
    map.setProjection({ type: projection });
    frame(map, projection);
  }, [projection, ready]);

  // the globe turning on itself, the way the earth does: the side facing us moves east, so the
  // longitude in the middle of the view goes down. It yields to the user — a drag, a pinch or a zoom
  // animation runs untouched, and the turn resumes from wherever they left it.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !ready || !spin || projection !== 'globe') return;
    let frame = 0;
    let last = performance.now();
    const turn = (now) => {
      const dt = Math.min(100, now - last) / 1000; // a background tab resumes without a jump
      last = now;
      if (!map.isEasing() && !map.dragPan.isActive() && !map.touchZoomRotate.isActive()) {
        const center = map.getCenter();
        // read from the ref, so a change of speed does not restart the loop
        const step = DEGREES_PER_SECOND * latest.current.speed * dt;
        map.setCenter([((center.lng - step + 540) % 360) - 180, center.lat]);
      }
      frame = requestAnimationFrame(turn);
    };
    frame = requestAnimationFrame(turn);
    return () => cancelAnimationFrame(frame);
  }, [spin, projection, ready]);

  const hoverValue = hover ? values.get(hover.iso) : undefined;

  return (
    <div className="worldmap">
      <div ref={container} className="worldmap-canvas" />
      {hover && (
        <div className="worldmap-tip" style={{ left: hover.x, top: hover.y }}>
          <strong>{label ? label(hover.iso) : hover.iso}</strong>
          <span className={hoverValue === undefined ? 'faint' : ''}>{hoverValue === undefined ? 'nothing in this period' : format(hoverValue)}</span>
        </div>
      )}
      {palette && max > 0 && (
        <div className="worldmap-legend">
          <span>{format(scale === 'linear' ? 0 : Math.min(...values.values()))}</span>
          <span className="ramp">
            {Array.from({ length: STEPS }, (_, i) => (
              <i key={i} style={{ background: palette.ramp(i / (STEPS - 1)) }} />
            ))}
          </span>
          <span>{format(max)}</span>
        </div>
      )}
    </div>
  );
}
