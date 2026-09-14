import type {NextConfig} from 'next';
const config:NextConfig={output:process.env.DEVPILOT_NODE_BUILD==='true'?'standalone':undefined,poweredByHeader:false};
export default config;
