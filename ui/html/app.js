'use strict';

// Состояние рабочей сессии. Источник правды — сервер; здесь только то,
// что показано на экране: открытый набор, выбранный вариант, выделение, раскладка.
const state = {
    projects: [],
    project: null,
    input: null,
    schema: null,
    settings: null,
    advanced: false,
    job: null,
    resultGeo: null,
    result: null,
    // Результаты по алгоритмам живут рядом: новый расчёт вытесняет только результат того же
    // алгоритма, остальные остаются видимыми. Ключ — алгоритм, значение — задание и его
    // выгрузки, подгружаемые один раз.
    results: {},
    running: {},
    algorithm: null,
    failed: null,
    variant: 0,
    sideA: 'variant:0',
    sideB: null,
    compare: false,
    selected: [],
    imported: {geo: null, outcome: null},
    layers: {restrictions: true, existing: true, points: true, plan: true, labels: false},
    columns: {},
    folded: {},
    reference: null
};

const DN_STEPS = [
    {max: 150, color: 'var(--dn-1)', title: 'до 150'},
    {max: 300, color: 'var(--dn-2)', title: '200—300'},
    {max: 500, color: 'var(--dn-3)', title: '400—500'},
    {max: 99999, color: 'var(--dn-4)', title: 'от 600'}
];

const FILL = {
    oks: '#dadde0', water: '#bcd7ea', railway: '#d0c2b0', park: '#cfe0c8',
    social_area: '#e0d8c4', prohibited_site: '#e4d0d0', road: '#dcdcdc', tram_tracks: '#d6cfc5'
};

const LAYER_TITLES = {
    restrictions: 'Ограничения', existing: 'Существующая сеть',
    points: 'Точки подключения', plan: 'Схема', labels: 'Подписи'
};

const $ = (id) => document.getElementById(id);
const esc = (v) => String(v === null || v === undefined ? '' : v)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');

function dnColor(dn) {
    for (const step of DN_STEPS) if (dn <= step.max) return step.color;
    return DN_STEPS[DN_STEPS.length - 1].color;
}

function num(value, digits = 0) {
    if (value === null || value === undefined || Number.isNaN(Number(value))) return '—';
    return Number(value).toLocaleString('ru-RU', {
        minimumFractionDigits: digits, maximumFractionDigits: digits
    }).replace(/ /g, ' ');
}

const money = (v) => (v === null || v === undefined ? '—' : num(Math.round(v)));

async function api(path, options) {
    const response = await fetch(path, options);
    if (!response.ok) {
        let message = response.statusText;
        try {
            const body = await response.json();
            message = body.message || body.error || message;
        } catch (ignored) { /* тело не json */ }
        throw new Error(message);
    }
    return response.status === 204 ? null : response.json();
}

function status(text) {
    $('status').textContent = text || '';
}

// --- вьюпорт ---------------------------------------------------------------

let origin = [37.6, 55.7];
const sharedView = {x: 0, y: 0, k: 1};

function project(lon, lat) {
    return [
        (lon - origin[0]) * Math.cos(origin[1] * Math.PI / 180) * 111320,
        -(lat - origin[1]) * 110540
    ];
}

/** Сколько пикселей нужно сдвинуть мышь, чтобы нажатие стало перетаскиванием, а не кликом. */
const DRAG_THRESHOLD = 4;

function isAdditive(e) {
    return e.shiftKey || e.ctrlKey || e.metaKey;
}

/**
 * Область схемы. Указатель захватывается только когда нажатие превратилось в
 * перетаскивание: если захватить сразу, браузер отдаёт клик самой области, а не
 * объекту под курсором, и выделение не работает.
 * Без модификатора перетаскивание двигает схему, с Shift, Ctrl или Cmd — рисует рамку.
 */
function createViewport(section) {
    const canvas = section.querySelector('[data-role="canvas"]');
    canvas.innerHTML = '<svg><g data-role="world"></g></svg><div class="rubber" hidden></div>';
    const world = canvas.querySelector('[data-role="world"]');
    const rubber = canvas.querySelector('.rubber');
    const vp = {section, canvas, world, bounds: null, key: null, markerScale: null};

    let press = null;
    const finish = () => {
        press = null;
        rubber.hidden = true;
        canvas.classList.remove('dragging', 'boxing');
    };

    canvas.addEventListener('pointerdown', (e) => {
        if (e.button !== 0) {
            return;
        }
        // Без этого браузер начинает своё выделение текста и заливает серым всю страницу.
        e.preventDefault();
        const textSelection = window.getSelection();
        if (textSelection && !textSelection.isCollapsed) {
            textSelection.removeAllRanges();
        }
        const target = objectUnder(e.clientX, e.clientY);
        press = {
            id: e.pointerId, x: e.clientX, y: e.clientY, vx: sharedView.x, vy: sharedView.y,
            item: target ? {kind: target.dataset.pickKind, id: target.dataset.pickId} : null,
            additive: isAdditive(e), moved: false
        };
    });

    canvas.addEventListener('pointermove', (e) => {
        if (!press || e.pointerId !== press.id) {
            return;
        }
        if (e.buttons === 0) {
            finish();
            return;
        }
        const dx = e.clientX - press.x;
        const dy = e.clientY - press.y;
        if (!press.moved) {
            if (Math.hypot(dx, dy) < DRAG_THRESHOLD) {
                return;
            }
            press.moved = true;
            try {
                canvas.setPointerCapture(e.pointerId);
            } catch (ignored) {
                // указатель уже отпущен: перетаскивание всё равно доведём до конца
            }
            canvas.classList.add(press.additive ? 'boxing' : 'dragging');
        }
        if (press.additive) {
            const box = canvas.getBoundingClientRect();
            rubber.hidden = false;
            rubber.style.left = Math.min(press.x, e.clientX) - box.left + 'px';
            rubber.style.top = Math.min(press.y, e.clientY) - box.top + 'px';
            rubber.style.width = Math.abs(dx) + 'px';
            rubber.style.height = Math.abs(dy) + 'px';
            return;
        }
        sharedView.x = press.vx + dx;
        sharedView.y = press.vy + dy;
        applyView();
    });

    canvas.addEventListener('pointerup', (e) => {
        if (!press || e.pointerId !== press.id) {
            return;
        }
        if (!press.moved) {
            if (press.item) {
                select(press.item, press.additive ? 'toggle' : 'replace', {reveal: false});
            } else if (!press.additive) {
                select([], 'replace');
            }
        } else if (press.additive) {
            const rect = {
                left: Math.min(press.x, e.clientX), right: Math.max(press.x, e.clientX),
                top: Math.min(press.y, e.clientY), bottom: Math.max(press.y, e.clientY)
            };
            select(itemsInRect(vp, rect), 'add', {reveal: false});
        }
        finish();
    });
    canvas.addEventListener('pointercancel', finish);
    canvas.addEventListener('lostpointercapture', (e) => {
        if (press && press.moved && e.pointerId === press.id) {
            finish();
        }
    });

    canvas.addEventListener('wheel', (e) => {
        e.preventDefault();
        const box = canvas.getBoundingClientRect();
        const px = e.clientX - box.left;
        const py = e.clientY - box.top;
        const current = sharedView.k > 0 ? sharedView.k : 1;
        const next = Math.min(120, Math.max(0.01, current * Math.exp(-e.deltaY * 0.0015)));
        const ratio = next / sharedView.k;
        sharedView.x = px - (px - sharedView.x) * ratio;
        sharedView.y = py - (py - sharedView.y) * ratio;
        sharedView.k = next;
        applyView();
    }, {passive: false});
    canvas.addEventListener('dragstart', (e) => e.preventDefault());
    canvas.addEventListener('selectstart', (e) => e.preventDefault());
    return vp;
}

/**
 * Объект под курсором. Зоны клика у точечных объектов шире самих значков и могут
 * перекрываться: тогда выбирается значок, центр которого ближе к курсору. Точечные
 * объекты важнее линий, линии важнее площадных ограничений.
 */
function objectUnder(clientX, clientY) {
    const candidates = document.elementsFromPoint(clientX, clientY).filter((el) => el.dataset && el.dataset.pickKind);
    const markers = candidates.filter((el) => el.closest('.marker'));
    if (markers.length) {
        let best = markers[0];
        let bestDistance = Infinity;
        for (const el of markers) {
            const r = el.getBoundingClientRect();
            const distance = Math.hypot(r.left + r.width / 2 - clientX, r.top + r.height / 2 - clientY);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = el;
            }
        }
        return best;
    }
    return candidates.find((el) => el.dataset.pickKind !== 'restriction') || candidates[0] || null;
}

/**
 * Объекты, попавшие в рамку. Линия попадает, если хотя бы одна её точка внутри рамки;
 * ограничения рамкой не выделяются — их сотни, и они засоряли бы выделение.
 */
function itemsInRect(vp, rect) {
    const inside = (x, y) => x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
    const found = [];
    const seen = new Set();
    vp.world.querySelectorAll('[data-pick-kind]').forEach((el) => {
        const kind = el.dataset.pickKind;
        if (kind === 'restriction') {
            return;
        }
        const key = kind + ':' + el.dataset.pickId;
        if (seen.has(key)) {
            return;
        }
        let hit = false;
        if (el.tagName === 'path') {
            const matrix = el.getScreenCTM();
            const length = el.getTotalLength();
            const samples = Math.min(60, Math.max(2, Math.ceil(length * sharedView.k / 6)));
            for (let i = 0; i <= samples && !hit; i++) {
                const p = el.getPointAtLength(length * i / samples);
                hit = inside(p.x * matrix.a + p.y * matrix.c + matrix.e, p.x * matrix.b + p.y * matrix.d + matrix.f);
            }
        } else {
            const r = el.getBoundingClientRect();
            hit = inside(r.left + r.width / 2, r.top + r.height / 2);
        }
        if (hit) {
            seen.add(key);
            found.push({kind, id: el.dataset.pickId});
        }
    });
    return found;
}

const viewports = [];

function applyView() {
    for (const vp of viewports) {
        vp.world.setAttribute('transform',
            `translate(${sharedView.x} ${sharedView.y}) scale(${sharedView.k})`);
        if (vp.markerScale !== sharedView.k) {
            resizeMarkers(vp);
        }
        const scale = vp.section.querySelector('[data-role="scale"]');
        if (scale) scale.textContent = `${Math.round(100 * sharedView.k)} px = 100 м`;
    }
}

/**
 * Точки, камеры и врезки рисуются в пикселях экрана, а не в метрах: иначе при общем
 * виде они сжимаются до пикселя и по ним невозможно попасть мышью.
 */
function resizeMarkers(vp) {
    const inverse = 1 / (sharedView.k || 1);
    vp.world.querySelectorAll('.marker').forEach((g) => {
        g.setAttribute('transform', `translate(${g.dataset.x} ${g.dataset.y}) scale(${inverse})`);
    });
    vp.markerScale = sharedView.k;
}

/**
 * Подгоняет схему под окно. Сразу после загрузки страницы раскладка может быть ещё
 * не посчитана и размер области нулевой — тогда ждём следующего кадра, иначе масштаб
 * уходит в минус и схема пропадает.
 */
function fitView(attempt = 0) {
    const vp = viewports[0];
    if (!vp || !vp.bounds) return;
    const box = vp.canvas.getBoundingClientRect();
    if ((box.width < 80 || box.height < 80) && attempt < 60) {
        requestAnimationFrame(() => fitView(attempt + 1));
        return;
    }
    const width = vp.bounds.maxX - vp.bounds.minX || 1;
    const height = vp.bounds.maxY - vp.bounds.minY || 1;
    const usableWidth = Math.max(80, box.width) - 60;
    const usableHeight = Math.max(80, box.height) - 60;
    sharedView.k = Math.max(0.01, Math.min(usableWidth / width, usableHeight / height));
    sharedView.x = box.width / 2 - (vp.bounds.minX + width / 2) * sharedView.k;
    sharedView.y = box.height / 2 - (vp.bounds.minY + height / 2) * sharedView.k;
    applyView();
}

// --- сцена -----------------------------------------------------------------

function track(vp, x, y) {
    if (!vp.bounds) vp.bounds = {minX: x, minY: y, maxX: x, maxY: y};
    else {
        vp.bounds.minX = Math.min(vp.bounds.minX, x);
        vp.bounds.minY = Math.min(vp.bounds.minY, y);
        vp.bounds.maxX = Math.max(vp.bounds.maxX, x);
        vp.bounds.maxY = Math.max(vp.bounds.maxY, y);
    }
}

function pathOf(vp, coords) {
    return coords.map((c, i) => {
        const [x, y] = project(c[0], c[1]);
        track(vp, x, y);
        return `${i ? 'L' : 'M'}${x.toFixed(1)} ${y.toFixed(1)}`;
    }).join('');
}

const itemKey = (kind, id) => kind + ':' + String(id);

function pick(kind, id) {
    return `class="hit" data-pick-kind="${kind}" data-pick-id="${esc(id)}"${isPicked(kind, id) ? ' data-on="1"' : ''}`;
}

function isPicked(kind, id) {
    return state.selected.some((item) => item.kind === kind && String(item.id) === String(id));
}

/**
 * Маркер постоянного экранного размера. Прозрачный круг побольше — зона клика,
 * фигура внутри рисуется в пикселях. Выделенный маркер обводится акцентом.
 */
function marker(vp, coordinates, kind, id, shape, label) {
    const [x, y] = project(coordinates[0], coordinates[1]);
    track(vp, x, y);
    const inverse = 1 / (sharedView.k || 1);
    const ring = isPicked(kind, id)
        ? '<circle r="9" fill="none" stroke="var(--accent)" stroke-width="2" pointer-events="none"/>' : '';
    const text = label ? `<text x="9" y="3.5" font-size="10" fill="#5b6169" pointer-events="none">${esc(label)}</text>` : '';
    return `<g class="marker" data-x="${x.toFixed(2)}" data-y="${y.toFixed(2)}"
        transform="translate(${x.toFixed(2)} ${y.toFixed(2)}) scale(${inverse})">
        <circle r="10" fill="transparent" ${pick(kind, id)}/>${shape}${ring}${text}</g>`;
}

function line(d, color, width, dash, attrs, extra = '') {
    return `<path d="${d}" fill="none" stroke="transparent" stroke-width="14"
        vector-effect="non-scaling-stroke" ${attrs} ${extra}/>
        <path d="${d}" fill="none" stroke="${color}" stroke-width="${width}"
        ${dash ? `stroke-dasharray="${dash}"` : ''} stroke-linejoin="round" stroke-linecap="round"
        vector-effect="non-scaling-stroke" pointer-events="none" ${extra}/>`;
}

/** Набор объектов схемы для стороны: расчётный вариант или загруженная схема. */
function sceneFeatures(key) {
    if (!key) return [];
    if (key === 'imported') return state.imported.geo ? state.imported.geo.features : [];
    const index = key.startsWith('variant:') ? Number(key.split(':')[1]) : state.variant;
    const id = variantIds()[index];
    if (!id || !state.resultGeo) return [];
    return state.resultGeo.features.filter((f) => f.properties.variant_id === id);
}

function draw(vp) {
    if (!state.input) {
        vp.world.innerHTML = '';
        return;
    }
    vp.bounds = null;
    const parts = [];
    const plan = sceneFeatures(vp.key);

    if (state.layers.restrictions) {
        for (const f of state.input.features) {
            const p = f.properties;
            if (p.object_type !== 'restriction') continue;
            const type = p.restriction_type || 'oks';
            const on = isPicked('restriction', p.id);
            for (const d of polygonPaths(vp, f.geometry)) {
                parts.push(`<path d="${d}" fill="${FILL[type] || '#e0e0e0'}"
                    stroke="${on ? 'var(--accent)' : '#c3c6c9'}" stroke-width="${on ? 1.6 : 0.6}"
                    vector-effect="non-scaling-stroke" ${pick('restriction', p.id)}/>`);
            }
        }
    }

    if (state.layers.existing) {
        for (const f of state.input.features) {
            const p = f.properties;
            if (p.object_type !== 'heat_network') continue;
            const on = isPicked('existing', p.id);
            parts.push(line(pathOf(vp, f.geometry.coordinates),
                on ? 'var(--accent)' : '#8a9199', on ? 4 : 2.5, null, pick('existing', p.id)));
        }
        for (const f of state.input.features) {
            const p = f.properties;
            if (p.object_type === 'heat_chamber') {
                parts.push(marker(vp, f.geometry.coordinates, 'chamber', p.id,
                    '<rect x="-3.5" y="-3.5" width="7" height="7" fill="#1b1d20" pointer-events="none"/>'));
            }
            if (p.object_type === 'source') {
                parts.push(marker(vp, f.geometry.coordinates, 'source', p.id,
                    '<circle r="6" fill="var(--error)" stroke="#fff" stroke-width="1.5" pointer-events="none"/>'));
            }
        }
    }

    if (state.layers.points) {
        const missed = new Set((summaryOf(vp.key).unconnected_oks_ids || []).map(String));
        for (const f of state.input.features) {
            const p = f.properties;
            if (p.object_type !== 'oks_connection_point') continue;
            const bad = missed.has(String(p.id));
            parts.push(marker(vp, f.geometry.coordinates, 'point', p.id,
                `<circle r="4.5" fill="${bad ? 'var(--error)' : '#1b1d20'}" stroke="#fff" stroke-width="1.5" pointer-events="none"/>`,
                state.layers.labels ? `${p.id} · ${p.flow_tph}` : null));
        }
    }

    if (state.layers.plan) {
        for (const f of plan) {
            const p = f.properties;
            if (p.object_type !== 'heat_network') continue;
            const on = isPicked('segment', p.id);
            parts.push(line(pathOf(vp, f.geometry.coordinates),
                on ? 'var(--accent)' : dnColor(p.diameter), on ? 5 : 3.5,
                p.laying_method === 'special' ? '6 3' : null,
                pick('segment', p.id), `data-seg="${esc(p.id)}"`));
        }
        for (const f of plan) {
            const p = f.properties;
            if (!f.geometry || f.geometry.type !== 'Point') continue;
            const at = f.geometry.coordinates;
            if (p.object_type === 'heat_chamber') {
                // Камера на существующей трубе — место присоединения: выделяем акцентом.
                const accent = p.on_existing_network;
                parts.push(marker(vp, at, 'newchamber', p.id,
                    `<rect x="-4" y="-4" width="8" height="8" fill="${accent ? 'var(--accent)' : '#fff'}"
                        stroke="${accent ? '#fff' : '#1b1d20'}" stroke-width="1.5" pointer-events="none"/>`));
            } else if (p.object_type === 'technical_node') {
                parts.push(marker(vp, at, 'node', p.id,
                    '<circle r="2.5" fill="#5b6169" pointer-events="none"/>'));
            }
        }
    }

    vp.world.innerHTML = parts.join('');
    vp.markerScale = sharedView.k;
    const empty = vp.section.querySelector('.vp-empty');
    if (empty) empty.remove();
    if (!plan.length && vp.key) {
        vp.canvas.insertAdjacentHTML('beforeend',
            '<div class="vp-empty">Схемы нет</div>');
    }
}

function polygonPaths(vp, geometry) {
    if (!geometry) return [];
    const polygons = geometry.type === 'MultiPolygon' ? geometry.coordinates : [geometry.coordinates];
    return polygons.map((poly) => poly.map((ring) => pathOf(vp, ring) + 'Z').join(' '));
}

function redraw() {
    for (const vp of viewports) draw(vp);
    applyView();
}

// --- варианты и сводки ------------------------------------------------------

function variantIds() {
    if (!state.resultGeo) return [];
    return state.resultGeo.features
        .filter((f) => f.properties.object_type === 'variant_summary')
        .map((f) => f.properties.variant_id);
}

function summaryOf(key) {
    if (!key) return {};
    if (key === 'imported') return state.imported.outcome ? asSummary(state.imported.outcome.variant) : {};
    const index = Number(key.split(':')[1]);
    const id = variantIds()[index];
    if (!id || !state.resultGeo) return {};
    return state.resultGeo.features
        .map((f) => f.properties)
        .find((p) => p.object_type === 'variant_summary' && p.variant_id === id) || {};
}

/** Приводит вариант из JSON к тем же именам полей, что в выгрузке GeoJSON. */
function asSummary(v) {
    return {
        variant_id: v.variantId, rank: v.rank,
        segments_cost: v.segmentsCost,
        construction_cost: v.constructionCost,
        chamber_construction_cost: v.chamberConstructionCost,
        existing_chamber_tie_in_count: v.existingChamberTieInCount,
        existing_chamber_tie_in_cost: v.existingChamberTieInCost,
        unconnected_penalty: v.unconnectedPenalty,
        calculated_cost: v.calculatedCost,
        new_network_length: v.newNetworkLength,
        score: v.score,
        unconnected_oks_ids: v.unconnected || [],
        explanation: v.explanation, warnings: v.warnings || [],
        strategy: v.strategy
    };
}

function keyTitle(key) {
    if (key === 'imported') return 'Загруженная схема';
    const index = Number(key.split(':')[1]);
    const s = summaryOf(key);
    const strategy = strategyOf(index);
    return `Вариант ${s.variant_id || index + 1}${strategy ? ' · ' + strategy : ''}`;
}

function strategyOf(index) {
    if (state.result && state.result.variants[index]) return state.result.variants[index].strategy;
    if (state.job && state.job.summary && state.job.summary.variants
        && state.job.summary.variants[index]) return state.job.summary.variants[index].strategy;
    return '';
}

function availableKeys() {
    const keys = variantIds().map((_, i) => 'variant:' + i);
    if (state.imported.outcome) keys.push('imported');
    return keys;
}

// --- выбор объекта ----------------------------------------------------------

/**
 * Меняет выделение. mode: replace — только эти объекты, add — добавить,
 * toggle — снять с уже выделенных и добавить остальные.
 */
function select(items, mode = 'replace', options = {}) {
    const list = (Array.isArray(items) ? items : (items ? [items] : []))
        .map((item) => ({kind: item.kind, id: String(item.id)}));
    if (mode === 'replace') {
        state.selected = list;
    } else {
        const next = new Map(state.selected.map((item) => [itemKey(item.kind, item.id), item]));
        for (const item of list) {
            const key = itemKey(item.kind, item.id);
            if (mode === 'toggle' && next.has(key)) {
                next.delete(key);
            } else {
                next.set(key, item);
            }
        }
        state.selected = [...next.values()];
    }
    renderInspector();
    redraw();
    markRows(options.scroll !== false ? list : []);
    if (options.reveal !== false && state.selected.length) {
        revealOnMap(state.selected);
    }
}

/** Отмечает строки таблиц, соответствующие выделению, и подводит к первой из новых. */
function markRows(justSelected) {
    document.querySelectorAll('tr[data-kind][data-id]').forEach((row) => {
        row.setAttribute('aria-selected', String(isPicked(row.dataset.kind, row.dataset.id)));
    });
    const first = justSelected.find((item) => isPicked(item.kind, item.id));
    if (!first) {
        return;
    }
    const row = document.querySelector(
        `tr[data-kind="${first.kind}"][data-id="${CSS.escape(String(first.id))}"]`);
    if (row) {
        unfoldAround(row);
        row.scrollIntoView({block: 'nearest'});
    }
}

/** Раскрывает свёрнутую секцию и панель, в которых лежит строка. */
function unfoldAround(row) {
    const body = row.closest('.section-body');
    if (body && body.classList.contains('folded')) {
        const key = body.dataset.section;
        state.folded[key] = false;
        body.classList.remove('folded');
        const head = document.querySelector(`[data-fold-section="${CSS.escape(key)}"]`);
        if (head) {
            head.classList.remove('folded');
        }
    }
    const pane = row.closest('.dock-pane');
    if (pane && pane.classList.contains('folded')) {
        const head = pane.querySelector('[data-fold]');
        pane.classList.remove('folded');
        if (head) {
            head.classList.remove('folded');
            state.folded[head.dataset.fold] = false;
        }
    }
}

/**
 * Показывает выделенное на схеме: если оно помещается при текущем масштабе, вид просто
 * сдвигается; если нет — отдаляется ровно настолько, чтобы всё выделенное было видно.
 */
function revealOnMap(items) {
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const item of items) {
        const feature = featureOf(item);
        if (!feature || !feature.geometry) {
            continue;
        }
        eachCoordinate(feature.geometry.coordinates, (lon, lat) => {
            const [x, y] = project(lon, lat);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        });
    }
    if (minX === Infinity) {
        return;
    }
    const box = viewports[0].canvas.getBoundingClientRect();
    const margin = 50;
    const left = minX * sharedView.k + sharedView.x;
    const top = minY * sharedView.k + sharedView.y;
    const right = maxX * sharedView.k + sharedView.x;
    const bottom = maxY * sharedView.k + sharedView.y;
    if (left >= margin && top >= margin && right <= box.width - margin && bottom <= box.height - margin) {
        return;
    }
    const width = Math.max(1, maxX - minX);
    const height = Math.max(1, maxY - minY);
    const fits = width * sharedView.k <= box.width - 2 * margin && height * sharedView.k <= box.height - 2 * margin;
    if (!fits) {
        sharedView.k = Math.max(0.01, Math.min((box.width - 2 * margin) / width, (box.height - 2 * margin) / height));
    }
    sharedView.x = box.width / 2 - (minX + width / 2) * sharedView.k;
    sharedView.y = box.height / 2 - (minY + height / 2) * sharedView.k;
    applyView();
}

function eachCoordinate(coordinates, visit) {
    if (typeof coordinates[0] === 'number') {
        visit(coordinates[0], coordinates[1]);
        return;
    }
    for (const inner of coordinates) {
        eachCoordinate(inner, visit);
    }
}

const PLAN_TYPES = {
    segment: 'heat_network', newchamber: 'heat_chamber', node: 'technical_node'
};

const INPUT_TYPES = {
    restriction: ['restriction'], existing: ['heat_network'],
    chamber: ['heat_chamber'], source: ['source'], point: ['oks_connection_point']
};

/** Объект выделения в исходных данных или в схеме: сначала основная сторона, потом вторая. */
function featureOf(item) {
    const same = (value) => String(value) === String(item.id);
    if (INPUT_TYPES[item.kind] && state.input) {
        return state.input.features.find((f) => INPUT_TYPES[item.kind].includes(f.properties.object_type)
            && (item.kind === 'source' || same(f.properties.id))) || null;
    }
    const type = PLAN_TYPES[item.kind];
    if (!type) {
        return null;
    }
    for (const key of [state.sideA, state.compare ? state.sideB : null]) {
        const found = sceneFeatures(key).find((f) => f.properties.object_type === type
            && same(f.properties.id));
        if (found) {
            return found;
        }
    }
    return null;
}

function findProperties(kind, id) {
    const feature = featureOf({kind, id});
    return feature ? feature.properties : null;
}

/** По идентификатору из диагностики определяет, что это за объект исходных данных. */
function itemOfInputId(id) {
    if (!state.input || id === null || id === undefined) {
        return null;
    }
    const feature = state.input.features.find((f) => String(f.properties.id) === String(id));
    if (!feature) {
        return null;
    }
    for (const [kind, types] of Object.entries(INPUT_TYPES)) {
        if (types.includes(feature.properties.object_type)) {
            return {kind, id: String(id)};
        }
    }
    return null;
}

/** Объекты схемы, идентификаторы которых упомянуты в тексте замечания. */
function itemsMentioned(text) {
    const words = new Set(text.split(/[^\w]+/));
    const found = [];
    for (const f of sceneFeatures(state.sideA)) {
        const p = f.properties;
        const kind = Object.keys(PLAN_TYPES).find((k) => PLAN_TYPES[k] === p.object_type);
        if (kind && words.has(String(p.id))) {
            found.push({kind, id: String(p.id)});
        }
    }
    return found;
}

/** Все объекты группы: статья стоимости, диаметр, вид ограничения, секция таблицы. */
function groupItems(group) {
    const plan = sceneFeatures(state.sideA).map((f) => f.properties);
    const ofType = (type, kind, idField = 'id') => plan
        .filter((p) => p.object_type === type).map((p) => ({kind, id: p[idField]}));
    const [name, value] = group.split(':');
    switch (name) {
        case 'segments':
            return ofType('heat_network', 'segment');
        case 'newchambers':
            return ofType('heat_chamber', 'newchamber');
        case 'attachments':
            return plan.filter((p) => p.object_type === 'heat_chamber' && p.on_existing_network)
                .map((p) => ({kind: 'newchamber', id: p.id}));
        case 'nodes':
            return ofType('technical_node', 'node');
        case 'unconnected':
            return (summaryOf(state.sideA).unconnected_oks_ids || []).map((id) => ({kind: 'point', id}));
        case 'dn':
            return plan.filter((p) => p.object_type === 'heat_network' && String(p.diameter) === value)
                .map((p) => ({kind: 'segment', id: p.id}));
        case 'dnstep': {
            const step = DN_STEPS[Number(value)];
            const lower = Number(value) > 0 ? DN_STEPS[Number(value) - 1].max : 0;
            return plan.filter((p) => p.object_type === 'heat_network' && p.diameter > lower && p.diameter <= step.max)
                .map((p) => ({kind: 'segment', id: p.id}));
        }
        case 'points':
        case 'existing':
        case 'chambers': {
            const kind = name === 'points' ? 'point' : (name === 'existing' ? 'existing' : 'chamber');
            return (state.input ? state.input.features : [])
                .filter((f) => INPUT_TYPES[kind].includes(f.properties.object_type))
                .map((f) => ({kind, id: f.properties.id}));
        }
        case 'restriction':
            return (state.input ? state.input.features : [])
                .filter((f) => f.properties.object_type === 'restriction'
                    && (f.properties.restriction_type || 'без типа') === value)
                .map((f) => ({kind: 'restriction', id: f.properties.id}));
        case 'plan':
            return [].concat(groupItems('segments'), groupItems('newchambers'), groupItems('nodes'));
        default:
            return [];
    }
}

const FIELD_TITLES = {
    id: 'Идентификатор', object_type: 'Тип', variant_id: 'Вариант', diameter: 'Условный диаметр, мм',
    flow_tph: 'Расход, т/ч', length: 'Длина, м', laying_method: 'Прокладка', cost: 'Стоимость, руб',
    start_node_id: 'Начальный узел', end_node_id: 'Конечный узел', restriction_type: 'Вид ограничения',
    address: 'Адрес', name: 'Наименование', existing_object_id: 'Существующий объект',
    on_existing_network: 'Камера присоединения', turn_angles: 'Повороты на участке, °',
    node_angle: 'Угол в начальном узле, °',
    depth_start: 'Глубина в начале, м', depth_end: 'Глубина в конце, м'
};

const KIND_TITLES = {
    restriction: 'Пространственное ограничение', existing: 'Существующий участок сети',
    chamber: 'Существующая камера', source: 'Источник теплоснабжения',
    point: 'Точка подключения', segment: 'Новый участок сети',
    newchamber: 'Новая камера', node: 'Технический узел'
};

function renderInspector() {
    const box = $('inspector');
    if (!state.input || !state.selected.length) {
        box.innerHTML = '<div class="note">Выберите объект на схеме или в таблице. '
            + 'Shift, Ctrl или Cmd добавляют к выделению, с ними же перетаскивание рисует рамку.</div>';
        return;
    }
    if (state.selected.length === 1) {
        const item = state.selected[0];
        const props = findProperties(item.kind, item.id);
        if (!props) {
            box.innerHTML = '<div class="note">Объект не найден в текущей схеме.</div>';
            return;
        }
        // Углы показываются отдельной строкой ниже, в свойствах их не дублируем.
        const hidden = ['object_type', 'turn_angles', 'node_angle'];
        let rows = Object.entries(props)
            .filter(([k]) => !hidden.includes(k))
            .map(([k, v]) => `<dt>${esc(FIELD_TITLES[k] || k)}</dt><dd>${esc(formatField(k, v))}</dd>`)
            .join('');
        if (item.kind === 'segment') {
            rows += angleRows(featureOf(item));
        }
        box.innerHTML = `<div class="group">${KIND_TITLES[item.kind] || 'Объект'}</div><dl class="props">${rows}</dl>`;
        return;
    }

    const available = Math.max(200, box.clientWidth - 2);
    const byKind = new Map();
    let totalLength = 0;
    let totalCost = 0;
    const rows = state.selected.map((item) => {
        const props = findProperties(item.kind, item.id) || {};
        const summary = byKind.get(item.kind) || {count: 0, length: 0, cost: 0};
        summary.count++;
        summary.length += props.length || 0;
        summary.cost += props.cost || 0;
        byKind.set(item.kind, summary);
        totalLength += props.length || 0;
        totalCost += props.cost || 0;
        return {
            kind: item.kind, id: item.id,
            cells: {
                what: esc(SHORT_KIND[item.kind] || item.kind),
                id: esc(item.id),
                dn: props.diameter ? num(props.diameter) : '—',
                len: props.length ? num(props.length, 1) : '—',
                cost: props.cost ? money(props.cost) : '—'
            }
        };
    });
    const kinds = [...byKind.entries()].map(([kind, sum]) => ({
        cells: {
            what: esc(KIND_TITLES[kind] || kind), count: num(sum.count),
            len: sum.length ? num(sum.length, 1) : '—', cost: sum.cost ? money(sum.cost) : '—'
        }
    }));
    kinds.push({
        className: 'total',
        cells: {what: 'Итого', count: num(state.selected.length),
            len: totalLength ? num(totalLength, 1) : '—', cost: totalCost ? money(totalCost) : '—'}
    });

    box.innerHTML = section('inspector.summary', 'Выделено', state.selected.length,
            table('inspector.summary', remember('inspector.summary', [
                {key: 'what', title: 'Объекты', flex: true, max: 200},
                {key: 'count', title: 'Шт', num: true},
                {key: 'len', title: 'Длина, м', num: true},
                {key: 'cost', title: 'Стоимость, руб', num: true}
            ]), kinds, available))
        + section('inspector.list', 'Список', null,
            table('inspector.list', remember('inspector.list', [
                {key: 'what', title: 'Объект', max: 90},
                {key: 'id', title: 'Идентификатор', flex: true, max: 140},
                {key: 'dn', title: 'Ду', num: true},
                {key: 'len', title: 'Длина, м', num: true},
                {key: 'cost', title: 'Стоимость, руб', num: true}
            ]), rows, available));
    bindTables(box);
}

/** Направление отрезка в плане, радианы. */
function heading(a, b) {
    return Math.atan2(b[1] - a[1], b[0] - a[0]);
}

/** Угол поворота между двумя направлениями, градусы от 0 до 180. */
function turnBetween(h1, h2) {
    let d = Math.abs((h2 - h1) * 180 / Math.PI) % 360;
    return d > 180 ? 360 - d : d;
}

/** Острый угол между двумя направлениями без учёта стороны, градусы от 0 до 90. */
function acuteBetween(h1, h2) {
    let d = Math.abs((h2 - h1) * 180 / Math.PI) % 180;
    return d > 90 ? 180 - d : d;
}

function degrees(value) {
    return value.toFixed(1).replace(/\.0$/, '') + '°';
}

/**
 * Углы участка новой сети. Приложение допускает дополнительные свойства и при проверке
 * их игнорирует, поэтому повороты и угол в узле пишутся прямо в выгрузку; здесь они
 * только показываются. Угол к существующей трубе считается по геометрии.
 */
function angleRows(feature) {
    if (!feature || !feature.geometry || feature.geometry.type !== 'LineString') {
        return '';
    }
    const p = feature.properties;
    const turns = (p.turn_angles || []).map((a) => degrees(a));
    let rows = `<dt>Повороты на участке</dt><dd>${turns.length ? esc(turns.join(', ')) : 'нет'}</dd>`;
    if (p.node_angle !== undefined && p.node_angle !== null) {
        rows += `<dt>Угол в начальном узле</dt><dd>${esc(degrees(p.node_angle))} к пришедшему участку</dd>`;
    }
    const pts = feature.geometry.coordinates.map((c) => project(c[0], c[1]));
    const first = heading(pts[0], pts[1]);
    const chamber = sceneFeatures(state.sideA).find((f) => f.properties.object_type === 'heat_chamber'
        && f.properties.variant_id === p.variant_id && f.properties.id === p.start_node_id
        && f.properties.on_existing_network);
    if (chamber && state.input) {
        const at = project(chamber.geometry.coordinates[0], chamber.geometry.coordinates[1]);
        let best = null;
        for (const f of state.input.features) {
            if (f.properties.object_type !== 'heat_network') continue;
            const line = f.geometry.coordinates.map((c) => project(c[0], c[1]));
            for (let i = 1; i < line.length; i++) {
                const d = distanceToSegment(at, line[i - 1], line[i]);
                if (d < 1.5 && (best === null || d < best.d)) {
                    best = {d, h: heading(line[i - 1], line[i])};
                }
            }
        }
        if (best) {
            rows += `<dt>Угол к существующей трубе</dt><dd>${esc(degrees(acuteBetween(best.h, first)))}</dd>`;
        }
    }
    return rows;
}

function distanceToSegment(p, a, b) {
    const dx = b[0] - a[0];
    const dy = b[1] - a[1];
    const l2 = dx * dx + dy * dy;
    const t = l2 === 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2));
    return Math.hypot(a[0] + t * dx - p[0], a[1] + t * dy - p[1]);
}

function formatField(key, value) {
    if (value === null || value === undefined) return '—';
    if (key === 'cost') return money(value);
    if (key === 'laying_method') return value === 'special' ? 'специальный проход' : 'обычная';
    if (typeof value === 'number') return num(value, Number.isInteger(value) ? 0 : 2);
    if (Array.isArray(value)) return value.join(', ') || '—';
    return value;
}

const SHORT_KIND = {
    restriction: 'огранич.', existing: 'сущ. участок', chamber: 'сущ. камера', source: 'источник',
    point: 'точка', segment: 'участок', newchamber: 'камера', node: 'узел'
};

// --- нижние доки ------------------------------------------------------------

/**
 * Сворачиваемый блок. Состояние запоминается, чтобы после пересчёта панель
 * выглядела так же, как её оставил инженер.
 */
function section(key, title, count, body, selectGroup) {
    const folded = !!state.folded[key];
    const counterText = count === undefined || count === null ? '' : esc(count);
    const counter = !counterText ? ''
        : `<span class="count${selectGroup !== undefined ? ' pickable' : ''}"${selectGroup !== undefined
            ? ` data-select-group="${esc(selectGroup)}"` : ''}>${counterText}</span>`;
    return `<div class="group${folded ? ' folded' : ''}" data-fold-section="${key}">
            <span class="twisty"></span><span class="group-title" title="${esc(title)}">${esc(title)}</span>${counter}</div>
        <div class="section-body${folded ? ' folded' : ''}" data-section="${key}">${body}</div>`;
}

const CHAR_TEXT = 7.0;
const CHAR_MONO = 7.4;

/** Ширина столбца по самому длинному значению, а не по остатку места. */
function columnWidths(key, columns, rows, available) {
    const saved = state.columns[key] || {};
    const widths = columns.map((c) => {
        if (saved[c.key]) {
            return saved[c.key];
        }
        const perChar = c.num ? CHAR_MONO : CHAR_TEXT;
        let longest = c.title.length;
        for (const row of rows) {
            const raw = String(row.cells[c.key] === undefined || row.cells[c.key] === null ? '' : row.cells[c.key])
                .replace(/<[^>]*>/g, '');
            longest = Math.max(longest, raw.length);
        }
        const width = Math.ceil(longest * perChar) + 18;
        return Math.max(c.min || 46, Math.min(c.max || 260, width));
    });
    const total = widths.reduce((a, b) => a + b, 0);
    if (available && total < available) {
        let flexible = columns.findIndex((c) => c.flex);
        if (flexible < 0) {
            flexible = columns.findIndex((c) => !c.num);
        }
        if (flexible >= 0) {
            widths[flexible] += available - total;
        }
    }
    return widths;
}

function table(key, columns, rows, available) {
    if (!rows.length) {
        return '<div class="note">Нет данных.</div>';
    }
    const widths = columnWidths(key, columns, rows, available);
    const total = widths.reduce((a, b) => a + b, 0);
    const cols = widths.map((w) => `<col style="width:${w}px">`).join('');
    const head = columns.map((c, i) => `<th class="${c.num ? 'num' : ''}" title="${esc(c.title)}">${esc(c.title)}`
        + (i < columns.length - 1 ? `<span class="col-grip" data-grip="${i}"></span>` : '') + '</th>').join('');
    const body = rows.map((r) => {
        const cells = columns.map((c) => {
            const value = r.cells[c.key] === undefined || r.cells[c.key] === null ? '—' : r.cells[c.key];
            const plain = String(value).replace(/<[^>]*>/g, '');
            return `<td class="${c.num ? 'num' : ''}${c.dim ? ' dim' : ''}" title="${esc(plain)}">${value}</td>`;
        }).join('');
        const attrs = r.kind ? `data-pick data-kind="${r.kind}" data-id="${esc(r.id)}"`
            : (r.group ? `data-group="${esc(r.group)}"` : '');
        return `<tr ${attrs} ${r.className ? `class="${r.className}"` : ''}>${cells}</tr>`;
    }).join('');
    return `<div class="table-wrap"><table data-table="${esc(key)}" style="width:${total}px">
        <colgroup>${cols}</colgroup>
        <thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
}

/** Ширина столбцов тянется мышью и запоминается для этой таблицы. */
function bindTables(root) {
    root.querySelectorAll('tr[data-pick]').forEach((row) => {
        row.setAttribute('aria-selected', String(isPicked(row.dataset.kind, row.dataset.id)));
        row.onclick = (e) => select({kind: row.dataset.kind, id: row.dataset.id},
            isAdditive(e) ? 'toggle' : 'replace', {scroll: false});
    });
    root.querySelectorAll('tr[data-group]').forEach((row) => {
        row.onclick = (e) => select(groupItems(row.dataset.group), isAdditive(e) ? 'add' : 'replace', {scroll: false});
    });
    root.querySelectorAll('[data-select-group]').forEach((counter) => {
        counter.onclick = (e) => {
            e.stopPropagation();
            select(groupItems(counter.dataset.selectGroup), isAdditive(e) ? 'add' : 'replace', {scroll: false});
        };
    });
    root.querySelectorAll('[data-mention]').forEach((note) => {
        note.onclick = (e) => select(JSON.parse(note.dataset.mention), isAdditive(e) ? 'add' : 'replace');
    });
    root.querySelectorAll('[data-note-object]').forEach((note) => {
        note.onclick = (e) => {
            const item = itemOfInputId(note.dataset.noteObject);
            if (item) {
                select(item, isAdditive(e) ? 'toggle' : 'replace');
            }
        };
    });
    root.querySelectorAll('[data-grip]').forEach((grip) => {
        const table = grip.closest('table');
        const index = Number(grip.dataset.grip);
        const key = table.dataset.table;
        draggable(grip,
            () => ({x: null, w: table.querySelectorAll('col')[index].getBoundingClientRect().width}),
            (e, startState) => {
                if (startState.x === null) {
                    startState.x = e.clientX;
                }
                const next = Math.max(40, Math.round(startState.w + (e.clientX - startState.x)));
                const cols = table.querySelectorAll('col');
                cols[index].style.width = next + 'px';
                let total = 0;
                cols.forEach((c) => {
                    total += parseFloat(c.style.width) || c.getBoundingClientRect().width;
                });
                table.style.width = Math.round(total) + 'px';
                const columnKey = table.querySelectorAll('th')[index].getAttribute('title');
                state.columns[key] = state.columns[key] || {};
                state.columns[key][columnKeyOf(key, index)] = next;
                void columnKey;
            });
    });
    root.querySelectorAll('[data-fold-section]').forEach((head) => {
        head.onclick = () => {
            const key = head.dataset.foldSection;
            state.folded[key] = !state.folded[key];
            head.classList.toggle('folded', state.folded[key]);
            const body = root.querySelector(`[data-section="${CSS.escape(key)}"]`);
            if (body) {
                body.classList.toggle('folded', state.folded[key]);
            }
        };
    });
}

/** Ключи столбцов таблицы, чтобы запоминать ширину по смыслу, а не по номеру. */
const tableColumns = {};

function columnKeyOf(tableKey, index) {
    const columns = tableColumns[tableKey];
    return columns && columns[index] ? columns[index].key : String(index);
}

function remember(tableKey, columns) {
    tableColumns[tableKey] = columns;
    return columns;
}

function renderData() {
    const box = $('dataBody');
    if (!state.project) {
        box.innerHTML = '<div class="note">Загрузите набор данных.</div>';
        return;
    }
    const available = Math.max(200, box.clientWidth - 2);
    const features = state.input ? state.input.features : [];
    const nets = features.filter((x) => x.properties.object_type === 'heat_network');
    const chambers = features.filter((x) => x.properties.object_type === 'heat_chamber');
    const points = features.filter((x) => x.properties.object_type === 'oks_connection_point');
    const restrictions = features.filter((x) => x.properties.object_type === 'restriction');
    const diagnostics = state.project.diagnostics || [];
    const missed = new Set((summaryOf(state.sideA).unconnected_oks_ids || []).map(String));
    const plan = sceneFeatures(state.sideA).map((f) => f.properties);
    const byType = {};
    restrictions.forEach((x) => {
        const type = x.properties.restriction_type || 'без типа';
        byType[type] = (byType[type] || 0) + 1;
    });
    const totalFlow = points.reduce((sum, x) => sum + (x.properties.flow_tph || 0), 0);

    const pointColumns = remember('data.points', [
        {key: 'id', title: 'Объект', max: 90},
        {key: 'flow', title: 'Расход, т/ч', num: true},
        {key: 'state', title: 'Состояние', flex: true}
    ]);
    const netColumns = remember('data.net', [
        {key: 'id', title: 'Участок', max: 120},
        {key: 'dn', title: 'Ду, мм', num: true},
        {key: 'pad', title: '', flex: true, min: 0}
    ]);
    const restrictionColumns = remember('data.restrictions', [
        {key: 'type', title: 'Вид', flex: true},
        {key: 'count', title: 'Объектов', num: true}
    ]);

    box.innerHTML =
        section('data.points', 'Точки подключения', `${points.length} · ${num(totalFlow, 2)} т/ч`,
            table('data.points', pointColumns, points.map((x) => ({
                kind: 'point', id: x.properties.id,
                cells: {
                    id: esc(x.properties.id),
                    flow: num(x.properties.flow_tph, 2),
                    state: missed.has(String(x.properties.id))
                        ? '<span class="delta-up">маршрут не найден</span>'
                        : (state.resultGeo ? 'подключена' : '—')
                }
            })), available), 'points')
        + section('data.net', 'Существующая сеть', `${nets.length} уч. · ${chambers.length} кам.`,
            table('data.net', netColumns, nets.map((x) => ({
                kind: 'existing', id: x.properties.id,
                cells: {id: esc(x.properties.id), dn: num(x.properties.diameter), pad: ''}
            })), available), 'existing')
        + section('data.restrictions', 'Ограничения', restrictions.length,
            table('data.restrictions', restrictionColumns,
                Object.entries(byType).map(([type, count]) => ({
                    group: 'restriction:' + type,
                    cells: {type: esc(type), count: num(count)}
                })), available))
        + section('data.diagnostics', 'Проверка данных', diagnostics.length,
            diagnostics.length
                ? diagnostics.map((d) => {
                    const pickable = d.objectId && itemOfInputId(d.objectId);
                    const level = d.level === 'error' ? 'error' : d.level === 'warning' ? 'warn' : 'plain';
                    return `<div class="note ${level}${pickable ? ' pickable' : ''}"`
                        + `${pickable ? ` data-note-object="${esc(d.objectId)}"` : ''}>`
                        + `${esc(d.message)}${d.objectId ? ` · ${esc(d.objectId)}` : ''}</div>`;
                }).join('')
                : '<div class="note ok">Замечаний нет.</div>');
    bindTables(box);
}

/**
 * Врезки в существующие камеры: отдельного объекта в выгрузке нет (раздел 2.4),
 * они видны по тому, что конец нового участка лежит в узле с id существующей камеры.
 * Стоимость — 5 млн за каждый такой участок; общую сумму даёт сводка.
 */
function existingChamberTieIns(plan, summary) {
    if (!state.input) {
        return [];
    }
    const existing = new Set(state.input.features
        .filter((f) => f.properties.object_type === 'heat_chamber')
        .map((f) => String(f.properties.id)));
    const byChamber = new Map();
    for (const f of plan) {
        const p = f.properties;
        if (p.object_type !== 'heat_network') continue;
        for (const node of [p.start_node_id, p.end_node_id]) {
            if (!existing.has(String(node))) continue;
            const row = byChamber.get(String(node)) || {id: String(node), count: 0, dn: 0, cost: 0};
            row.count++;
            row.dn = Math.max(row.dn, p.diameter);
            byChamber.set(String(node), row);
        }
    }
    const total = summary.existing_chamber_tie_in_count || 0;
    const perTieIn = total ? (summary.existing_chamber_tie_in_cost || 0) / total : 0;
    for (const row of byChamber.values()) {
        row.cost = row.count * perTieIn;
    }
    return [...byChamber.values()];
}

/**
 * Переключатель алгоритмов. Каждый посчитанный алгоритм — отдельная плитка со своим
 * показателем, идущий расчёт — плитка без результата. Переключение мгновенное: выгрузка
 * каждого результата тянется один раз и остаётся в памяти.
 */
function algorithmChips() {
    const done = Object.keys(state.results);
    const running = Object.keys(state.running).filter((k) => !state.results[k]);
    if (!done.length && !running.length) {
        return '';
    }
    const chips = done.map((key) => {
        const entry = state.results[key];
        const best = ((entry.job.summary || {}).variants || [])[0];
        const note = best && best.score !== undefined ? ` · ${num(best.score, 3)}` : '';
        const busy = state.running[key] ? ' · считается' : '';
        return `<button class="chip" data-algorithm="${esc(key)}" aria-pressed="${key === state.algorithm}"
            title="${esc(entry.title)}">${esc(algorithmTitle(key))}${note}${busy}</button>`;
    });
    running.forEach((key) => {
        chips.push(`<button class="chip" disabled>${esc(algorithmTitle(key))} · считается</button>`);
    });
    return `<div class="row-actions" data-role="algorithms">${chips.join('')}</div>`;
}

/** Что сейчас считается: видно, не закрывая готовые результаты. */
function runningNote() {
    return Object.keys(state.running).map((key) => {
        const job = state.running[key].job;
        const last = (job.log || []).slice(-1)[0] || 'в очереди';
        return `<div class="note plain">${esc(algorithmTitle(key))} · ${job.progress}% · ${esc(last)}</div>`;
    }).join('');
}

function failedNote() {
    if (!state.failed) {
        return '';
    }
    return `<div class="note error">${esc(algorithmTitle(state.failed.algorithm))}: `
        + `${esc(state.failed.job.error || 'расчёт не выполнен')}</div>`;
}

function bindAlgorithms(box) {
    box.querySelectorAll('[data-algorithm]').forEach((chip) => {
        chip.onclick = () => activate(chip.dataset.algorithm);
    });
}

function renderResult() {
    const box = $('resultBody');
    const head = algorithmChips() + runningNote() + failedNote();
    if (!state.resultGeo) {
        box.innerHTML = head + (Object.keys(state.running).length
            ? '<div class="skeleton"></div><div class="skeleton"></div>'
            : '<div class="note">Расчёт ещё не запускался.</div>');
        bindAlgorithms(box);
        return;
    }

    const available = Math.max(300, box.clientWidth - 2);
    const keys = availableKeys();
    const base = summaryOf('variant:0');
    const key = state.sideA;
    const s = summaryOf(key);
    const plan = sceneFeatures(key);
    const segments = plan.filter((f) => f.properties.object_type === 'heat_network');
    const chambers = plan.filter((f) => f.properties.object_type === 'heat_chamber');
    const nodes = plan.filter((f) => f.properties.object_type === 'technical_node');
    const byDn = {};
    segments.forEach((f) => {
        byDn[f.properties.diameter] = (byDn[f.properties.diameter] || 0) + f.properties.length;
    });
    const warnings = s.warnings || [];

    const variantColumns = remember('result.variants', [
        {key: 'name', title: 'Схема', flex: true, max: 320},
        {key: 'score', title: 'Показатель', num: true},
        {key: 'cost', title: 'Стоимость, руб', num: true},
        {key: 'length', title: 'Длина, м', num: true},
        {key: 'delta', title: 'Разница, руб', num: true}
    ]);
    const costColumns = remember('result.cost', [
        {key: 'a', title: 'Статья', flex: true, max: 320},
        {key: 'b', title: 'Значение', num: true}
    ]);
    const dnColumns = remember('result.dn', [
        {key: 'dn', title: 'Условный диаметр', flex: true, max: 220},
        {key: 'len', title: 'Длина, м', num: true},
        {key: 'cost', title: 'Стоимость, руб', num: true}
    ]);
    const segmentColumns = remember('result.segments', [
        {key: 'id', title: 'Участок', max: 120},
        {key: 'from', title: 'Начало', max: 110},
        {key: 'to', title: 'Конец', max: 110},
        {key: 'dn', title: 'Ду, мм', num: true},
        {key: 'flow', title: 'Расход, т/ч', num: true},
        {key: 'len', title: 'Длина, м', num: true},
        {key: 'lay', title: 'Прокладка', flex: true},
        {key: 'cost', title: 'Стоимость, руб', num: true}
    ]);
    const attachColumns = remember('result.attachments', [
        {key: 'id', title: 'Узел', max: 120},
        {key: 'how', title: 'Способ присоединения', flex: true},
        {key: 'dn', title: 'Ду, мм', num: true},
        {key: 'cost', title: 'Стоимость, руб', num: true}
    ]);
    const chamberColumns = remember('result.chambers', [
        {key: 'id', title: 'Камера', max: 120},
        {key: 'where', title: 'Расположение', flex: true},
        {key: 'dn', title: 'Ду, мм', num: true},
        {key: 'cost', title: 'Стоимость, руб', num: true}
    ]);

    box.innerHTML = head
        + section('result.variants', 'Варианты', keys.length,
            table('result.variants', variantColumns, keys.map((k) => {
                const item = summaryOf(k);
                const delta = item.calculated_cost === undefined ? null
                    : item.calculated_cost - base.calculated_cost;
                return {
                    kind: 'variantpick', id: k,
                    className: k === state.sideA ? 'picked' : '',
                    cells: {
                        name: esc(keyTitle(k)),
                        score: item.score === undefined ? '—' : num(item.score, 3),
                        cost: money(item.calculated_cost),
                        length: num(item.new_network_length, 1),
                        delta: delta === null || Math.abs(delta) < 1 ? '—'
                            : `<span class="${delta > 0 ? 'delta-up' : 'delta-down'}">${delta > 0 ? '+' : '−'}${money(Math.abs(delta))}</span>`
                    }
                };
            }), available))

        + section('result.cost', 'Стоимость · ' + keyTitle(key), null,
            table('result.cost', costColumns, [
                {group: 'segments', cells: {a: 'Новые участки, руб',
                    b: money(s.construction_cost - s.chamber_construction_cost - s.existing_chamber_tie_in_cost)}},
                {group: 'newchambers', cells: {a: 'Новые камеры, руб', b: money(s.chamber_construction_cost)}},
                {group: 'attachments', cells: {a: 'Врезки в существующие камеры, руб',
                    b: money(s.existing_chamber_tie_in_cost)}},
                {group: 'plan', cells: {a: 'Стоимость строительства, руб', b: money(s.construction_cost)}},
                {group: 'unconnected', cells: {a: 'Штраф за неподключённые, руб', b: money(s.unconnected_penalty)}},
                {group: 'plan', className: 'total', cells: {a: 'Итого, руб', b: money(s.calculated_cost)}},
                {group: 'segments', cells: {a: 'Новая сеть, м', b: num(s.new_network_length, 1)}},
                {cells: {a: 'Показатель ранжирования', b: num(s.score, 4)}}
            ], available))

        + (s.explanation ? section('result.explanation', 'Пояснение', null,
            `<div class="note plain">${esc(s.explanation)}</div>`) : '')

        + section('result.check', 'Проверка', warnings.length || null,
            warnings.length
                ? warnings.map((w) => {
                    const items = itemsMentioned(w);
                    return items.length
                        ? `<div class="note warn pickable" data-mention='${esc(JSON.stringify(items))}'>${esc(w)}</div>`
                        : `<div class="note warn">${esc(w)}</div>`;
                }).join('')
                : '<div class="note ok">Нарушений правил не найдено.</div>')

        + section('result.dn', 'Объёмы по диаметрам', Object.keys(byDn).length,
            table('result.dn', dnColumns, Object.entries(byDn).sort((a, b) => a[0] - b[0]).map(([dn, len]) => ({
                group: 'dn:' + dn,
                cells: {
                    dn: `<span style="display:inline-block;width:10px;height:3px;background:${dnColor(Number(dn))};vertical-align:middle;margin-right:6px"></span>Ду${dn}`,
                    len: num(len, 1),
                    cost: money(len * dnCost(Number(dn)))
                }
            })), available))

        + section('result.segments', 'Участки новой сети', segments.length,
            table('result.segments', segmentColumns, segments.map((f) => ({
                kind: 'segment', id: f.properties.id,
                cells: {
                    id: esc(f.properties.id),
                    from: esc(f.properties.start_node_id),
                    to: esc(f.properties.end_node_id),
                    dn: num(f.properties.diameter),
                    flow: num(f.properties.flow_tph, 2),
                    len: num(f.properties.length, 1),
                    lay: f.properties.laying_method === 'special' ? 'специальный проход' : 'обычная',
                    cost: money(f.properties.cost)
                }
            })), available), 'segments')

        + section('result.attachments', 'Присоединения к существующей сети',
            `${chambers.filter((f) => f.properties.on_existing_network).length} кам. · `
            + `${s.existing_chamber_tie_in_count || 0} врез.`,
            table('result.attachments', attachColumns,
                chambers.filter((f) => f.properties.on_existing_network).map((f) => ({
                    kind: 'newchamber', id: f.properties.id,
                    cells: {
                        id: esc(f.properties.id),
                        how: 'новая камера на участке ' + esc(f.properties.existing_object_id),
                        dn: num(f.properties.diameter),
                        cost: money(f.properties.cost)
                    }
                })).concat(existingChamberTieIns(plan, s).map((row) => ({
                    kind: 'chamber', id: row.id,
                    cells: {
                        id: esc(row.id),
                        how: `врезка в существующую камеру, участков ${row.count}`,
                        dn: num(row.dn),
                        cost: money(row.cost)
                    }
                }))), available), 'attachments')

        + section('result.chambers', 'Новые камеры', chambers.length,
            table('result.chambers', chamberColumns, chambers.map((f) => ({
                kind: 'newchamber', id: f.properties.id,
                cells: {
                    id: esc(f.properties.id),
                    where: f.properties.on_existing_network
                        ? 'на существующей сети' : 'на ветвлении новой сети',
                    dn: num(f.properties.diameter),
                    cost: money(f.properties.cost)
                }
            })), available), 'newchambers')

        + section('result.nodes', 'Технические узлы', nodes.length,
            table('result.nodes', remember('result.nodes', [
                {key: 'id', title: 'Узел', max: 120},
                {key: 'pad', title: '', flex: true, min: 0}
            ]), nodes.map((f) => ({
                kind: 'node', id: f.properties.id,
                cells: {id: esc(f.properties.id), pad: ''}
            })), available), 'nodes')

        + section('result.export', 'Выгрузка', null, `<div class="row-actions">
            <a class="tool" href="/api/jobs/${state.job.id}/result" download>GeoJSON расчёта</a>
            <a class="tool" href="/api/jobs/${state.job.id}/excel" download>Excel</a>
            ${state.imported.outcome ? `<a class="tool" href="/api/jobs/${state.job.id}/extras/imported/result" download>GeoJSON схемы</a>` : ''}
            <button class="tool" id="importPlan">Загрузить свою схему</button>
        </div>`);

    bindTables(box);
    bindAlgorithms(box);
    box.querySelectorAll('tr[data-kind="variantpick"]').forEach((row) => {
        row.onclick = () => {
            state.sideA = row.dataset.id;
            if (row.dataset.id.startsWith('variant:')) {
                state.variant = Number(row.dataset.id.split(':')[1]);
            }
            syncViewportKeys();
            renderResult();
            renderData();
            redraw();
        };
    });
    const importButton = $('importPlan');
    if (importButton) {
        importButton.onclick = () => importDialog();
    }
}

/** Стоимость метра нового строительства по справочнику, для ведомости объёмов. */
function dnCost(dn) {
    const list = state.reference ? state.reference.diameters : null;
    if (!list) {
        return 0;
    }
    const row = list.find((d) => d.dn === dn);
    return row ? row.newCost : 0;
}

// --- параметры --------------------------------------------------------------

function renderSettings() {
    const box = $('settings');
    if (!state.schema) {
        box.innerHTML = '<div class="skeleton"></div><div class="skeleton"></div><div class="skeleton"></div>';
        return;
    }
    box.innerHTML = state.schema.groups.map((group) => {
        const options = state.schema.options
            .filter((o) => o.group === group && (state.advanced || !o.advanced));
        if (!options.length) {
            return '';
        }
        return section('settings.' + group, group, options.length, options.map(field).join(''));
    }).join('');
    bindTables(box);
    box.querySelectorAll('[data-key]').forEach((element) => {
        element.onchange = () => {
            const key = element.dataset.key;
            state.settings[key] = element.type === 'checkbox' ? element.checked
                : element.type === 'number' ? Number(element.value) : element.value;
        };
    });
}

function field(option) {
    const value = state.settings[option.key];
    let control;
    if (option.type === 'choice') {
        control = `<select data-key="${option.key}">${option.values.map((v) =>
            `<option value="${v.key}" ${v.key === value ? 'selected' : ''}>${esc(v.title)}</option>`).join('')}</select>`;
    } else if (option.type === 'flag') {
        control = `<input type="checkbox" data-key="${option.key}" ${value ? 'checked' : ''}>`;
    } else {
        control = `<input type="number" data-key="${option.key}" value="${value}"
            min="${option.min}" max="${option.max}" step="${option.step}">`;
    }
    return `<div class="field"><label>${esc(option.title)}${option.unit ? ', ' + esc(option.unit) : ''}</label>
        <span class="control">${control}</span></div>`;
}

// --- загруженная схема ------------------------------------------------------

function importDialog() {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.geojson,.json,application/geo+json';
    input.onchange = () => input.files[0] && importPlan(input.files[0]);
    input.click();
}

async function importPlan(file) {
    if (!state.job) return;
    status('разбор схемы…');
    const form = new FormData();
    form.append('file', file);
    try {
        state.imported.outcome = await api(`/api/jobs/${state.job.id}/imported`, {method: 'POST', body: form});
        state.imported.geo = await api(`/api/jobs/${state.job.id}/extras/imported/result`);
        state.sideB = 'imported';
        state.compare = true;
        $('compare').setAttribute('aria-pressed', 'true');
        const d = state.imported.outcome.costDelta;
        status(d === undefined ? 'схема загружена'
            : `загруженная схема: ${d >= 0 ? 'дороже' : 'дешевле'} на ${money(Math.abs(d))} руб`);
        syncViewportKeys();
        renderResult();
        redraw();
    } catch (e) {
        status('');
        $('resultBody').insertAdjacentHTML('afterbegin', `<div class="note error">${esc(e.message)}</div>`);
    }
}

// --- каркас -----------------------------------------------------------------

function syncViewportKeys() {
    const sections = document.querySelectorAll('.viewport');
    viewports[0].key = state.sideA;
    sections[0].querySelector('[data-role="caption"]').textContent = state.resultGeo ? keyTitle(state.sideA) : 'Исходные данные';

    const second = sections[1];
    second.hidden = !state.compare;
    if (state.compare) {
        if (!state.sideB || state.sideB === state.sideA) {
            const keys = availableKeys().filter((k) => k !== state.sideA);
            state.sideB = keys[0] || null;
        }
        viewports[1].key = state.sideB;
        const caption = second.querySelector('[data-role="caption"]');
        const keys = availableKeys();
        caption.innerHTML = `<select id="sideBPick" style="font:inherit;text-transform:none">${keys.map((k) =>
            `<option value="${k}" ${k === state.sideB ? 'selected' : ''}>${esc(keyTitle(k))}</option>`).join('')}</select>`;
        const pickB = $('sideBPick');
        if (pickB) {
            pickB.style.pointerEvents = 'auto';
            pickB.onchange = () => {
                state.sideB = pickB.value;
                viewports[1].key = state.sideB;
                redraw();
            };
            caption.style.pointerEvents = 'auto';
        }
    }
    const pick = $('variantPick');
    const keys = availableKeys();
    pick.innerHTML = keys.map((k) => `<option value="${k}" ${k === state.sideA ? 'selected' : ''}>${esc(keyTitle(k))}</option>`).join('');
    pick.hidden = keys.length < 2;
}

async function loadProjects() {
    state.projects = await api('/api/projects');
    const pick = $('dataset');
    pick.innerHTML = state.projects.map((p) =>
        `<option value="${p.id}" ${state.project && state.project.id === p.id ? 'selected' : ''}>${esc(p.name)}</option>`).join('')
        || '<option value="">нет наборов</option>';
}

async function openProject(id) {
    status('загрузка набора…');
    state.project = await api('/api/projects/' + id);
    state.input = await api('/api/projects/' + id + '/input');
    state.result = null;
    state.resultGeo = null;
    state.job = null;
    state.results = {};
    state.running = {};
    state.algorithm = null;
    state.failed = null;
    state.variant = 0;
    state.sideA = 'variant:0';
    state.imported = {geo: null, outcome: null};
    state.selected = [];
    $('run').disabled = false;
    const bbox = state.project.bbox || [37.6, 55.7, 37.7, 55.75];
    origin = [(bbox[0] + bbox[2]) / 2, (bbox[1] + bbox[3]) / 2];
    syncViewportKeys();
    renderData();
    renderResult();
    renderInspector();
    redraw();
    fitView();
    status('');
    await loadResults(id);
}

/**
 * Готовые результаты по каждому алгоритму и расчёты, которые идут прямо сейчас.
 * Уже загруженные выгрузки сохраняются, если задание того же алгоритма не сменилось.
 */
async function loadResults(projectId, prefer) {
    let data;
    try {
        data = await api('/api/projects/' + projectId + '/results');
    } catch (ignored) {
        return;
    }
    const before = state.results;
    state.results = {};
    (data.results || []).forEach((r) => {
        const old = before[r.algorithm];
        const same = old && old.job && old.job.id === r.job.id;
        state.results[r.algorithm] = same
            ? Object.assign(old, {title: r.title, job: r.job})
            : {
                algorithm: r.algorithm, title: r.title, job: r.job,
                geo: null, detail: null, imported: {geo: null, outcome: null}
            };
    });
    state.running = {};
    (data.running || []).forEach((r) => {
        state.running[r.algorithm] = r;
    });
    const keys = Object.keys(state.results);
    if (!keys.length) {
        state.algorithm = null;
        renderResult();
        return;
    }
    const pick = prefer && state.results[prefer] ? prefer
        : (state.algorithm && state.results[state.algorithm] ? state.algorithm : keys[0]);
    await activate(pick);
}

/** Показывает результат выбранного алгоритма. Выгрузки тянутся один раз и остаются в памяти. */
async function activate(algorithm) {
    const entry = state.results[algorithm];
    if (!entry) {
        return;
    }
    state.algorithm = algorithm;
    state.job = entry.job;
    if (!entry.geo) {
        status('загрузка результата…');
        try {
            entry.geo = await api('/api/jobs/' + entry.job.id + '/result');
        } catch (e) {
            status('');
            $('resultBody').innerHTML = `<div class="note error">Результат не открылся: ${esc(e.message)}</div>`;
            return;
        }
        try {
            entry.detail = await api('/api/jobs/' + entry.job.id + '/variants');
        } catch (ignored) {
            entry.detail = null;
        }
        state.imported = entry.imported;
        await restoreExtras(entry.job.id);
    }
    state.resultGeo = entry.geo;
    state.result = entry.detail;
    state.imported = entry.imported;
    state.variant = 0;
    state.sideA = 'variant:0';
    state.selected = [];
    syncViewportKeys();
    renderResult();
    renderData();
    renderInspector();
    redraw();
    status(entry.detail
        ? `${entry.title}: ${num(entry.detail.millis)} мс · сетка ${String(entry.detail.gridStep).replace('.', ',')} м`
        : entry.title);
}

/** Название алгоритма по ключу из справочника параметров. */
function algorithmTitle(key) {
    const list = (state.schema && state.schema.algorithms) || [];
    const found = list.find((a) => a.key === key);
    return found ? found.title : key;
}

/** Загруженная схема живёт на сервере: после перезагрузки страницы её видно снова. */
async function restoreExtras(jobId) {
    try {
        const extras = await api('/api/jobs/' + jobId + '/extras');
        if (extras.imported) {
            state.imported.outcome = {variant: extras.imported};
            state.imported.geo = await api('/api/jobs/' + jobId + '/extras/imported/result');
        }
    } catch (ignored) { /* дополнительных схем нет */ }
}

async function upload(file) {
    status('загрузка файла…');
    const form = new FormData();
    form.append('file', file);
    try {
        const project = await api('/api/projects', {method: 'POST', body: form});
        await loadProjects();
        $('dataset').value = project.id;
        await openProject(project.id);
    } catch (e) {
        status('');
        $('dataBody').innerHTML = `<div class="note error">Не удалось загрузить: ${esc(e.message)}</div>`;
    }
}

async function run() {
    if (!state.project) {
        return;
    }
    const algorithm = (state.settings && state.settings.algorithm) || 'OCTOGRID';
    state.failed = null;
    $('run').disabled = true;
    const bar = $('progress');
    bar.hidden = false;
    try {
        let job = await api('/api/projects/' + state.project.id + '/calculations', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(state.settings)
        });
        // Расчёт идёт отдельной строкой: то, что уже посчитано другими алгоритмами
        // и этим же раньше, остаётся на экране и в работе.
        for (; ;) {
            job = await api('/api/jobs/' + job.id);
            state.running[algorithm] = {algorithm, title: algorithmTitle(algorithm), job};
            bar.firstElementChild.style.transform = `scaleX(${job.progress / 100})`;
            renderResult();
            if (job.status === 'done' || job.status === 'failed') {
                break;
            }
            await new Promise((r) => setTimeout(r, 600));
        }
        delete state.running[algorithm];
        if (job.status === 'done') {
            // Прежний результат этого алгоритма вытеснен новым, остальные не тронуты.
            delete state.results[algorithm];
            await loadResults(state.project.id, algorithm);
            renderData();
            redraw();
        } else {
            state.failed = {algorithm, job};
            renderResult();
        }
    } catch (e) {
        status('');
        $('resultBody').innerHTML = `<div class="note error">Расчёт не запустился: ${esc(e.message)}</div>`;
    } finally {
        $('run').disabled = false;
        bar.hidden = true;
        bar.firstElementChild.style.transform = 'scaleX(0)';
    }
}

function renderLayers() {
    const box = document.querySelector('[data-role="layers"]');
    box.innerHTML = Object.keys(LAYER_TITLES).map((key) => {
        const swatch = key === 'restrictions' ? '<span class="dot box" style="background:#dadde0"></span>'
            : key === 'existing' ? '<span class="dot" style="background:#8a9199"></span>'
                : key === 'points' ? '<span class="dot box" style="background:#1b1d20;border-radius:50%"></span>'
                    : key === 'plan' ? '<span class="dot" style="background:var(--dn-3)"></span>' : '';
        return `<button class="chip" data-layer="${key}" aria-pressed="${state.layers[key]}">${swatch}${LAYER_TITLES[key]}</button>`;
    }).join('') + DN_STEPS.map((step, i) =>
        `<button class="chip" data-legend-group="dnstep:${i}"><span class="dot" style="background:${step.color}"></span>${step.title}</button>`).join('')
        + '<button class="chip" data-legend-group="attachments">'
        + '<span class="dot box" style="background:var(--accent)"></span>присоединения</button>';
    box.querySelectorAll('[data-legend-group]').forEach((chip) => {
        chip.onclick = (e) => select(groupItems(chip.dataset.legendGroup), isAdditive(e) ? 'add' : 'replace');
    });
    box.querySelectorAll('[data-layer]').forEach((button) => {
        button.onclick = () => {
            const key = button.dataset.layer;
            state.layers[key] = !state.layers[key];
            button.setAttribute('aria-pressed', String(state.layers[key]));
            redraw();
        };
    });
}

/**
 * Перетаскивание разделителей. Захват указателя и явное завершение по pointerup,
 * pointercancel и потере захвата: иначе состояние «тяну» залипает, и потом любое
 * движение мыши продолжает двигать раскладку.
 */
function draggable(handle, onStart, onMove) {
    let active = null;

    const finish = () => {
        active = null;
        handle.classList.remove('active');
    };

    handle.addEventListener('pointerdown', (e) => {
        if (e.button !== 0) return;
        e.preventDefault();
        active = {id: e.pointerId, start: onStart()};
        handle.setPointerCapture(e.pointerId);
        handle.classList.add('active');
    });
    handle.addEventListener('pointermove', (e) => {
        if (!active || e.pointerId !== active.id) return;
        if (e.buttons === 0) {
            finish();
            return;
        }
        onMove(e, active.start);
    });
    ['pointerup', 'pointercancel', 'lostpointercapture'].forEach((type) =>
        handle.addEventListener(type, (e) => {
            if (!active || e.pointerId === active.id) finish();
        }));
}

/**
 * Боковой док тянется за разделитель. direction: 1 — док слева и растёт при движении
 * вправо, -1 — док справа и растёт при движении влево.
 */
function sideDock(handle, dock, direction) {
    draggable(handle,
        () => ({x: null, w: dock.getBoundingClientRect().width}),
        (e, startState) => {
            if (startState.x === null) {
                startState.x = e.clientX;
            }
            const next = startState.w + direction * (e.clientX - startState.x);
            dock.style.width = Math.max(240, Math.min(window.innerWidth - 520, next)) + 'px';
            applyView();
        });
}

function splitters() {
    const bottom = $('dockBottom');
    draggable($('hsplit'),
        () => ({y: null, h: bottom.getBoundingClientRect().height}),
        (e, startState) => {
            if (startState.y === null) {
                startState.y = e.clientY;
            }
            const next = Math.max(90, Math.min(window.innerHeight - 220, startState.h - (e.clientY - startState.y)));
            bottom.style.height = next + 'px';
            applyView();
        });

    sideDock($('lsplit'), $('dockLeft'), 1);
    sideDock($('rsplit'), $('dockRight'), -1);

    document.querySelectorAll('[data-fold]').forEach((head) => {
        head.insertAdjacentHTML('afterbegin', '<span class="twisty"></span>');
        head.onclick = (e) => {
            if (e.target.tagName === 'INPUT') {
                return;
            }
            const pane = head.closest('.dock-pane');
            const key = head.dataset.fold;
            state.folded[key] = !state.folded[key];
            pane.classList.toggle('folded', state.folded[key]);
            head.classList.toggle('folded', state.folded[key]);
            applyView();
        };
    });
}

function start() {
    document.querySelectorAll('.viewport').forEach((section) => viewports.push(createViewport(section)));
    renderLayers();
    splitters();
    renderInspector();

    $('dataset').onchange = (e) => e.target.value && openProject(e.target.value);
    $('load').onclick = () => $('file').click();
    $('file').onchange = (e) => e.target.files[0] && upload(e.target.files[0]);
    $('run').onclick = run;
    $('variantPick').onchange = (e) => {
        state.sideA = e.target.value;
        if (e.target.value.startsWith('variant:')) state.variant = Number(e.target.value.split(':')[1]);
        syncViewportKeys();
        renderResult();
        renderData();
        redraw();
    };
    $('compare').onclick = () => {
        state.compare = !state.compare;
        $('compare').setAttribute('aria-pressed', String(state.compare));
        syncViewportKeys();
        redraw();
        applyView();
    };
    $('advanced').onchange = (e) => {
        state.advanced = e.target.checked;
        renderSettings();
    };

    document.addEventListener('keydown', (e) => {
        const typing = ['INPUT', 'SELECT', 'TEXTAREA'].includes(document.activeElement.tagName);
        if (e.key === 'Escape') {
            select([], 'replace');
        } else if (!typing && (e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') {
            e.preventDefault();
            select(groupItems('plan'), 'replace');
        }
    });
    document.addEventListener('dragover', (e) => e.preventDefault());
    document.addEventListener('drop', (e) => {
        e.preventDefault();
        if (e.dataTransfer.files[0]) upload(e.dataTransfer.files[0]);
    });
    window.addEventListener('resize', applyView);
    // Первая раскладка в браузере приходит позже загрузки скрипта: как только область
    // схемы получила реальный размер, вписываем её в окно.
    if (window.ResizeObserver) {
        let fitted = false;
        new ResizeObserver((entries) => {
            const box = entries[0].contentRect;
            if (!fitted && box.width > 80 && box.height > 80 && viewports[0].bounds) {
                fitted = true;
                fitView();
            }
        }).observe(viewports[0].canvas);
    }

    api('/api/reference').then((reference) => {
        state.reference = reference;
    }).catch(() => {
        state.reference = null;
    });
    api('/api/settings/schema').then((schema) => {
        state.schema = schema;
        state.settings = schema.defaults;
        renderSettings();
    });
    loadProjects().then(() => {
        if (state.projects.length) openProject(state.projects[0].id);
        else {
            renderData();
            renderResult();
        }
    });
}

start();
