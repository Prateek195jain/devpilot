import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import ts from 'typescript';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
const require=createRequire(import.meta.url);
const source=readFileSync(new URL('../components/devpilot/indexing-status.tsx',import.meta.url),'utf8');
const js=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.ReactJSX}}).outputText;
const compiled={exports:{}};new Function('require','module','exports',js)(require,compiled,compiled.exports);
const render=repo=>renderToStaticMarkup(React.createElement(compiled.exports.IndexingStatus,{repo}));
test('INDEXING leaves page usable and displays background progress',()=>{
 const html=render({indexingStatus:'INDEXING',latestIndexedCommitSha:'a'.repeat(40)});
 assert.match(html,/Indexing repository/);assert.match(html,/keep using DevPilot/);assert.match(html,/role="status"/);assert.doesNotMatch(html,/Backend is unavailable/);
});
test('READY displays repository knowledge ready',()=>assert.match(render({indexingStatus:'READY'}),/Repository knowledge ready/));
test('FAILED preserves previous knowledge message and safe rate limit category',()=>{
 const html=render({indexingStatus:'FAILED',latestIndexedCommitSha:'a'.repeat(40),lastIndexingErrorCategory:'RATE_LIMIT'});
 assert.match(html,/Using previous repository knowledge/);assert.match(html,/temporarily rate-limited/);assert.doesNotMatch(html,/Backend is unavailable/);
});
test('first-time FAILED suggests Retry Sync',()=>assert.match(render({indexingStatus:'FAILED',latestIndexedCommitSha:null}),/Repository indexing failed. Retry Sync./));
test('legacy metadata remains readable during rolling deployment',()=>assert.match(render({latestIndexedCommitSha:'a'.repeat(40)}),/Repository knowledge ready/));
