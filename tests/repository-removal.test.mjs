import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import ts from 'typescript';
import React from 'react';

const require = createRequire(import.meta.url);
function compile(filename, boundaries) {
    const source = readFileSync(new URL('../' + filename, import.meta.url), 'utf8');
    const js = ts.transpileModule(source, { compilerOptions: {
        module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
        jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true,
    } }).outputText;
    const compiled = { exports: {} };
    new Function('require', 'module', 'exports', js)(
        name => boundaries[name] ?? require(name), compiled, compiled.exports);
    return compiled.exports;
}
function descendants(node) {
    if (Array.isArray(node)) return node.flatMap(descendants);
    if (!React.isValidElement(node)) return [];
    return [node, ...descendants(node.props.children)];
}
function hookState() {
    let cursor = 0;
    const cells = [];
    const hooks = { ...React,
        useState(initial) {
            const index = cursor++;
            cells[index] ??= { value: typeof initial === 'function' ? initial() : initial };
            return [cells[index].value, value => {
                cells[index].value = typeof value === 'function' ? value(cells[index].value) : value;
            }];
        },
        useRef(initial) {
            const index = cursor++;
            cells[index] ??= { value: { current: initial } };
            return cells[index].value;
        },
        useEffect() {},
    };
    return { hooks, reset() { cursor = 0; } };
}
const stub = () => null;
const uiProxy = new Proxy({}, { get: () => stub });
const repo = { id: 'r', fullName: 'acme/service', defaultBranch: 'main' };

function removalHarness(disabled = false) {
    const state = hookState();
    const calls = [], errors = [], successes = [];
    let resolve, reject, removed = 0;
    const work = new Promise((yes, no) => { resolve = yes; reject = no; });
    const dialog = Object.fromEntries(['AlertDialog', 'AlertDialogAction', 'AlertDialogCancel',
        'AlertDialogContent', 'AlertDialogDescription', 'AlertDialogFooter', 'AlertDialogHeader',
        'AlertDialogTitle', 'AlertDialogTrigger'].map(name => [name, () => null]));
    const Button = () => null;
    const shared = compile('components/devpilot/screens/shared.tsx', {
        react: state.hooks, '@/components/ui/button': { Button }, '@/lib/devpilot-api': {},
        'lucide-react': uiProxy, '../ui': uiProxy,
        sonner: { toast: { error: message => errors.push(message), success: message => successes.push(message) } },
    });
    const { RemoveRepository } = compile('components/devpilot/screens/RemoveRepository.tsx', {
        react: state.hooks, '@/components/ui/button': { Button }, '@/components/ui/alert-dialog': dialog,
        '@/lib/devpilot-api': {
            repoApi: (id, suffix) => '/repositories/' + id + suffix,
            api: (...args) => { calls.push(args); return work; },
        }, './shared': shared,
    });
    let tree;
    const render = () => {
        state.reset(); tree = RemoveRepository({ repo, disabled, removed() { removed++; } }); return tree;
    };
    const find = type => {
        const result = descendants(tree).find(node => node.type === type);
        assert.ok(result); return result;
    };
    render();
    return {
        calls, errors, successes, resolve, reject, render,
        get removed() { return removed; },
        get open() { return find(dialog.AlertDialog).props.open; },
        get actionDisabled() { return find(dialog.AlertDialogAction).props.disabled; },
        get cancelDisabled() { return find(dialog.AlertDialogCancel).props.disabled; },
        get triggerDisabled() { return find(Button).props.disabled; },
        get description() { return find(dialog.AlertDialogDescription).props.children; },
        openDialog() { find(dialog.AlertDialog).props.onOpenChange(true); render(); },
        cancel() { find(dialog.AlertDialog).props.onOpenChange(false); render(); },
        confirm() {
            let prevented = false;
            find(dialog.AlertDialogAction).props.onClick({ preventDefault() { prevented = true; } });
            assert.equal(prevented, true); render();
        },
    };
}
const settle = () => new Promise(resolve => setImmediate(resolve));

test('removal requires confirmation and cancel performs no request', () => {
    const screen = removalHarness();
    assert.equal(screen.open, false);
    screen.openDialog();
    assert.equal(screen.open, true);
    assert.equal(screen.description, 'This removes the repository and its DevPilot analysis data. The GitHub repository will not be changed.');
    assert.equal(screen.calls.length, 0);
    screen.cancel();
    assert.equal(screen.open, false);
    assert.equal(screen.calls.length, 0);
    assert.equal(screen.removed, 0);
});

test('confirmed removal guards duplicates, disables pending controls, and reports success', async () => {
    const screen = removalHarness();
    screen.openDialog(); screen.confirm(); screen.confirm();
    assert.deepEqual(screen.calls, [['/repositories/r', 'DELETE']]);
    assert.equal(screen.actionDisabled, true);
    assert.equal(screen.cancelDisabled, true);
    assert.equal(screen.triggerDisabled, true);
    screen.cancel();
    assert.equal(screen.open, true);
    assert.equal(screen.removed, 0);
    screen.resolve(); await settle(); screen.render();
    assert.equal(screen.open, false);
    assert.equal(screen.removed, 1);
    assert.deepEqual(screen.successes, ['Repository removed from DevPilot. GitHub repository unchanged.']);
    assert.deepEqual(screen.errors, []);
});

test('failed removal keeps confirmation open, shows error, and allows retry', async () => {
    const screen = removalHarness();
    screen.openDialog(); screen.confirm();
    screen.reject(new Error('Repository removal failed. No data was removed.'));
    await settle(); screen.render();
    assert.equal(screen.open, true);
    assert.equal(screen.actionDisabled, false);
    assert.equal(screen.triggerDisabled, false);
    assert.equal(screen.removed, 0);
    assert.deepEqual(screen.successes, []);
    assert.deepEqual(screen.errors, ['Repository removal failed. No data was removed.']);
});

test('another pending detail action disables removal', () => {
    const screen = removalHarness(true);
    assert.equal(screen.triggerDisabled, true);
    screen.openDialog(); screen.confirm();
    assert.equal(screen.calls.length, 0);
});

test('successful removal refreshes the list, clears selection, and navigates to Repositories', () => {
    const state = hookState();
    let pathname = '/workspace/repositories', reloads = 0;
    const second = { ...repo, id: 'second', fullName: 'acme/second' };
    let inventory = [repo, second];
    const Repositories = () => null, RepositoryDetails = () => null, Choice = () => null;
    const resource = { data: { content: inventory }, loading: false, error: '', reload() {
        reloads++; inventory = [second]; this.data = { content: inventory };
    } };
    const hooks = { ...state.hooks, useSyncExternalStore: () => ({ user: { name: 'Test', role: 'USER' }, phase: 'ready' }) };
    const { default: App } = compile('components/devpilot/app.tsx', {
        react: hooks,
        'next/navigation': { usePathname: () => pathname, useRouter: () => ({ push: path => { pathname = path; }, replace() {} }) },
        'next/link': stub, 'lucide-react': uiProxy, '@/components/ui/sidebar': uiProxy,
        '@/components/ui/button': uiProxy, '@/components/ui/input': uiProxy, '@/components/ui/sonner': uiProxy,
        sonner: { toast: { error() {} } }, '@/lib/devpilot-api': { auth: {} },
        '@/lib/startup': { startup: { setUser() {} } }, './webmcp': { registerWorkspaceTools() {} },
        './ui': { Choice, Loading: stub },
        './use-resource': { useResource: path => path?.startsWith('/repositories?') ? resource : { loading: false, data: null } },
        './pages': { Repositories, RepositoryDetails },
    });
    const render = () => { state.reset(); return App(); };
    const find = (tree, type) => {
        const result = descendants(tree).find(node => node.type === type);
        assert.ok(result); return result;
    };
    find(render(), Repositories).props.select(repo.id);
    assert.equal(pathname, '/workspace/repository');
    find(render(), RepositoryDetails).props.removed();
    assert.equal(reloads, 1);
    assert.equal(pathname, '/workspace/repositories');
    assert.equal(find(render(), Choice).props.value, second.id);
    inventory = [];
    resource.data = { content: inventory };
    assert.ok(!descendants(render()).some(node => node.type === Choice));
});
