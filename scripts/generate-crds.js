const fs = require('fs');
const path = require('path');
const https = require('https');
const crypto = require('crypto');
const { execSync } = require('child_process');

const PROJECT_ROOT = process.cwd(); 

const OUTPUT_DIR = path.join(PROJECT_ROOT, 'generated/crds');
const TEMP_DIR = path.join(PROJECT_ROOT, 'temp_crds');
const CHECKSUM_FILE = path.join(PROJECT_ROOT, '.crd2pulumi-checksum');

const GW_VERSION = 'v1.1.0';
const GW_URL = `https://github.com/kubernetes-sigs/gateway-api/releases/download/${GW_VERSION}/experimental-install.yaml`;
const GW_FILE = 'gateway-api.yaml';

const CM_VERSION = 'v1.15.0';
const CM_URL = `https://github.com/cert-manager/cert-manager/releases/download/${CM_VERSION}/cert-manager.crds.yaml`;
const CM_FILE = 'cert-manager.yaml';

const downloadFile = (url, filename) => {
    return new Promise((resolve, reject) => {
        const destPath = path.join(TEMP_DIR, filename);
        const file = fs.createWriteStream(destPath);

        const request = (uri) => {
            https.get(uri, (response) => {
                if (response.statusCode === 301 || response.statusCode === 302) {
                    return request(response.headers.location);
                }
                if (response.statusCode !== 200) {
                    reject(new Error(`Failed to download ${uri}: ${response.statusCode}`));
                    return;
                }
                console.log(`Downloading ${filename}...`);
                response.pipe(file);
                file.on('finish', () => {
                    file.close();
                    resolve(destPath);
                });
            }).on('error', (err) => {
                fs.unlink(destPath, () => {});
                reject(err);
            });
        };
        request(url);
    });
};

const computeHash = (filePaths) => {
    const hash = crypto.createHash('sha256');
    filePaths.sort().forEach(fp => hash.update(fs.readFileSync(fp)));
    return hash.digest('hex');
};

async function main() {
    if (fs.existsSync(TEMP_DIR)) fs.rmSync(TEMP_DIR, { recursive: true, force: true });
    fs.mkdirSync(TEMP_DIR);

    try {
        const gwPath = await downloadFile(GW_URL, GW_FILE);
        const cmPath = await downloadFile(CM_URL, CM_FILE);
        const allFiles = [gwPath, cmPath];
        const newHash = computeHash(allFiles);

        let oldHash = null;
        if (fs.existsSync(CHECKSUM_FILE)) {
            oldHash = fs.readFileSync(CHECKSUM_FILE, 'utf8').trim();
        }

        if (oldHash === newHash && fs.existsSync(OUTPUT_DIR)) {
            console.log('CRDs unchanged. Skipping.');
        } else {
            console.log('Regenerating CRDs...');
            if (fs.existsSync(OUTPUT_DIR)) fs.rmSync(OUTPUT_DIR, { recursive: true, force: true });
            fs.mkdirSync(OUTPUT_DIR, { recursive: true });
            execSync(`crd2pulumi --nodejsPath "${OUTPUT_DIR}" --force "${gwPath}" "${cmPath}"`, { stdio: 'inherit' });

            const pkgPath = path.join(OUTPUT_DIR, 'package.json');
            if (fs.existsSync(pkgPath)) {
                const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
                if (pkg.scripts) delete pkg.scripts;
                fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2));
            }

            const tsconfig = {
                compilerOptions: {
                    target: "es2020",
                    module: "commonjs",
                    moduleResolution: "node",
                    declaration: true,
                    skipLibCheck: true,
                },
                include: ["**/*.ts"],
                exclude: ["node_modules"]
            };
            fs.writeFileSync(path.join(OUTPUT_DIR, 'tsconfig.json'), JSON.stringify(tsconfig, null, 2));
            
            try {
                execSync('npx tsc', { cwd: OUTPUT_DIR, stdio: 'inherit' });
            } catch (e) { console.warn("TSC warnings ignored."); }

            fs.writeFileSync(CHECKSUM_FILE, newHash);
            console.log(`Success!`);
        }
    } catch (error) {
        console.error(error);
        process.exit(1);
    } finally {
        if (fs.existsSync(TEMP_DIR)) fs.rmSync(TEMP_DIR, { recursive: true, force: true });
    }
}

main();