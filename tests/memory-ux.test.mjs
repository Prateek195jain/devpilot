import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import ts from 'typescript';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

const require = createRequire(import.meta.url);
const repo = { id: 'repository-b', fullName: 'acme/service', defaultBranch: 'main' };
const longTitle = 'Missing tests for CORS configuration change in backend/page-pulse/page-pulse/src/main/java/com/prateek/pagepulse/config/WebConfig.java';
const patterns = [
    { id: 'active', description: longTitle, status: 'ACTIVE', occurrences: 5, createdAt: '2026-01-01T00:00:00Z', lastSeen: '2026-01-05T00:00:00Z' },
    { id: 'archived', description: 'Archived memory pattern', status: 'ARCHIVED', occurrences: 2, createdAt: '2026-01-02T00:00:00Z', lastSeen: '2026-01-05T00:00:00Z' },
];

function renderScreen(filename, name) {
    const paths = [];
    const source = readFileSync(new URL('../components/devpilot/screens/' + filename, import.meta.url), 'utf8');
    const js = ts.transpileModule(source, { compilerOptions: {
        module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
        jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true,
    } }).outputText;
    const Button = ({ size, variant, ...props }) => {
        void size; void variant; return React.createElement('button', props);
    };
    const ui = {
        Badge: ({ children }) => React.createElement('span', null, children),
        Panel: ({ title, children }) => React.createElement('section', null, React.createElement('h2', null, title), children),
        EmptyState: ({ title }) => React.createElement('p', null, title),
        Pager: () => null,
        date: date => date,
        human: text => text,
    };
    const boundaries = {
        '@/components/ui/button': { Button },
        '@/components/ui/input': { Input: props => React.createElement('input', props) },
        '@/components/ui/alert-dialog': new Proxy({}, { get: () => () => null }),
        '@/lib/devpilot-api': { repoApi: (id, suffix) => '/repositories/' + id + suffix, api: async () => ({}) },
        'lucide-react': new Proxy({}, { get: () => () => null }),
        '../ui': ui,
        './shared': {
            State: ({ children }) => children,
            useAction: () => ({ busy: false, run: async () => {} }),
            severities: ['HIGH', 'LOW'],
        },
        '../use-resource': { useResource: path => {
            paths.push(path);
            let data;
            if (path === '/github') data = { connected: false };
            else if (path.endsWith('/stats')) data = { bySeverity: {} };
            else {
                const content = path.includes('/memories')
                    ? patterns.filter(pattern => !path.includes('status=ACTIVE') || pattern.status === 'ACTIVE')
                    : [];
                data = { content, totalElements: content.length, totalPages: 1 };
            }
            return { data, loading: false, error: '', reload() {} };
        } },
    };
    const compiled = { exports: {} };
    new Function('require', 'module', 'exports', js)(
        dependency => boundaries[dependency] ?? require(dependency), compiled, compiled.exports);
    const html = renderToStaticMarkup(React.createElement(compiled.exports[name], { repo, go() {} }));
    return { html, paths };
}

test('long memory title retains full text and uses wrapping rules without changing card controls', () => {
    const { html } = renderScreen('Memories.tsx', 'Memories');
    assert.ok(html.includes('<h2>' + longTitle + '</h2>'));
    assert.match(html, /class="panel memory-card"/);
    assert.match(html, /recorded occurrences/);
    assert.match(html, /First seen/);
    assert.match(html, /Last seen/);
    assert.match(html, /Archive pattern/);
    assert.match(html, /Reactivate pattern/);
    const css = readFileSync(new URL('../app/globals.css', import.meta.url), 'utf8');
    const titleRules = [...css.matchAll(/\.memory-card h2\s*\{([^}]+)\}/g)].map(match => match[1]).join(';');
    const cardRules = [...css.matchAll(/\.memory-card\s*\{([^}]+)\}/g)].map(match => match[1]).join(';');
    assert.match(titleRules, /overflow-wrap\s*:\s*anywhere/);
    assert.match(titleRules, /min-width\s*:\s*0/);
    assert.match(cardRules, /min-width\s*:\s*0/);
    assert.doesNotMatch(titleRules, /white-space\s*:\s*nowrap|text-overflow\s*:\s*ellipsis/);
});

test('Settings renders provider-neutral AI mode copy', () => {
    const { html } = renderScreen('IntegrationSettings.tsx', 'IntegrationSettings');
    assert.match(html, /<strong>AI mode<\/strong>/);
    assert.doesNotMatch(html, /OpenAI mode/);
    assert.match(html, /enables structured AI analysis and repository-aware answers/);
    assert.match(html, /Configure the provider and API key on the backend/);
    assert.match(html, /Repository context will be sent to the configured provider/);
});

test('Overview requests active memory for the open repository and renders only that context', () => {
    const { html, paths } = renderScreen('Dashboard.tsx', 'Dashboard');
    assert.ok(paths.includes('/repositories/repository-b/memories?size=3&status=ACTIVE'));
    assert.ok(paths.every(path => path.startsWith('/repositories/repository-b/')));
    assert.match(html, /COMPOUNDING CONTEXT/);
    assert.ok(html.includes(longTitle));
    assert.doesNotMatch(html, /Archived memory pattern/);
});

test('Engineering Memory keeps the unfiltered inventory and reactivation controls', () => {
    const { html, paths } = renderScreen('Memories.tsx', 'Memories');
    assert.deepEqual(paths, ['/repositories/repository-b/memories?page=0']);
    assert.ok(html.includes(longTitle));
    assert.match(html, /Archived memory pattern/);
    assert.match(html, /ARCHIVED/);
    assert.match(html, /Reactivate pattern/);
});
