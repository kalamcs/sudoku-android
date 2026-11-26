// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

/**
 * Renders the launcher icon assets from the two source SVGs.
 *
 *   node scripts/generate-icons.mjs
 *
 * Produces legacy square/round icons plus adaptive foregrounds at every
 * Android density bucket. The source path is resolved from this file, so it
 * works regardless of the current working directory.
 */
import { mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const resRoot = path.join(projectRoot, 'android/app/src/main/res');
const assetsRoot = path.join(projectRoot, 'assets');

// density bucket → [legacy launcher size, adaptive foreground size]
const densities = {
  mdpi: [48, 108],
  hdpi: [72, 162],
  xhdpi: [96, 216],
  xxhdpi: [144, 324],
  xxxhdpi: [192, 432],
};

const fullSvg = await readFile(path.join(assetsRoot, 'icon-full.svg'));
const foregroundSvg = await readFile(path.join(assetsRoot, 'icon-foreground.svg'));

const roundSvg = Buffer.from(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
     <defs>
       <clipPath id="c"><circle cx="512" cy="512" r="512"/></clipPath>
       <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
         <stop offset="0" stop-color="#6366f1"/>
         <stop offset="1" stop-color="#4338ca"/>
       </linearGradient>
     </defs>
     <g clip-path="url(#c)">
       <rect width="1024" height="1024" fill="url(#bg)"/>
       <g transform="translate(512 512) scale(0.9) translate(-512 -512)">
         <rect x="212" y="212" width="600" height="600" rx="56" fill="#ffffff"/>
         <g stroke="#c7d2fe" stroke-width="5">
           <path d="M278.7 212v600M345.3 212v600M412 212v600M478.7 212v600M545.3 212v600M612 212v600M678.7 212v600M745.3 212v600"/>
           <path d="M212 278.7h600M212 345.3h600M212 412h600M212 478.7h600M212 545.3h600M212 612h600M212 678.7h600M212 745.3h600"/>
         </g>
         <g stroke="#312e81" stroke-width="14" stroke-linecap="round">
           <path d="M412 212v600M612 212v600M212 412h600M212 612h600"/>
         </g>
         <rect x="212" y="212" width="600" height="600" rx="56" fill="none" stroke="#312e81" stroke-width="14"/>
         <g fill="#4f46e5" font-family="DejaVu Sans, Verdana, sans-serif" font-size="60" font-weight="bold" text-anchor="middle">
           <text x="245.3" y="265.3">5</text>
           <text x="512" y="265.3">3</text>
           <text x="378.7" y="332">8</text>
           <text x="578.7" y="398.7">1</text>
           <text x="312" y="532">4</text>
           <text x="712" y="532">9</text>
           <text x="445.3" y="665.3">7</text>
           <text x="778.7" y="732">2</text>
           <text x="512" y="798.7">6</text>
         </g>
       </g>
     </g>
   </svg>`
);

for (const [density, [legacySize, foregroundSize]] of Object.entries(densities)) {
  const dir = path.join(resRoot, `mipmap-${density}`);
  await mkdir(dir, { recursive: true });

  const render = (svg, size, file) =>
    sharp(svg, { density: 300 })
      .resize(size, size, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
      .png()
      .toFile(path.join(dir, file));

  await render(fullSvg, legacySize, 'ic_launcher.png');
  await render(roundSvg, legacySize, 'ic_launcher_round.png');
  await render(foregroundSvg, foregroundSize, 'ic_launcher_foreground.png');

  console.log(`mipmap-${density}: ${legacySize}px legacy, ${foregroundSize}px foreground`);
}

// Store a high-resolution source PNG for stores / docs.
await sharp(fullSvg, { density: 300 }).resize(1024, 1024).png().toFile(path.join(assetsRoot, 'icon.png'));
console.log('assets/icon.png: 1024px');
