import { prepareMobileProfile } from './profile-storage.mjs';
const home = process.env.DSH_HOME;
const runtime = process.env.DROY_RUNTIME_DIR;
if (!home || !runtime || !process.env.DROY_WEB_TOKEN || process.env.DROY_WEB_TOKEN.length < 32) throw new Error('缺少 DroyAgent 运行环境或会话凭据');
await prepareMobileProfile({ home, runtime });
process.stdout.write('DroyAgent mobile profile ready\n');
