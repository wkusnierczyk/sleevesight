// A QR code drawn as an SVG, dark on white with the standard quiet zone, whatever the page's theme:
// the keyboard's session code (#394) and the setup code of API keys (#436).

import { encode } from './qr.js';

const QUIET_ZONE = 4;

/** The QR code of [text] as an SVG element named [label], and its side in modules. */
export function qrSvg(text, label) {
  const modules = encode(text);
  const side = modules.length + 2 * QUIET_ZONE;
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', `0 0 ${side} ${side}`);
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', label);
  svg.setAttribute('shape-rendering', 'crispEdges');
  const back = document.createElementNS(svg.namespaceURI, 'rect');
  back.setAttribute('width', side);
  back.setAttribute('height', side);
  back.setAttribute('fill', '#fff');
  const path = document.createElementNS(svg.namespaceURI, 'path');
  let d = '';
  modules.forEach((row, y) =>
    row.forEach((dark, x) => {
      if (dark) d += `M${x + QUIET_ZONE} ${y + QUIET_ZONE}h1v1h-1z`;
    }),
  );
  path.setAttribute('d', d);
  path.setAttribute('fill', '#000');
  svg.append(back, path);
  return { svg, side };
}
