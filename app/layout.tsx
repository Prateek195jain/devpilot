import type {Metadata} from 'next';import './globals.css';
export const metadata:Metadata={title:'DevPilot — Engineering intelligence',description:'Connect your repositories, review code changes, and build persistent engineering memory.',icons:{icon:'/favicon.svg'}};
export default function RootLayout({children}:{children:React.ReactNode}){return <html lang="en"><body>{children}</body></html>}
