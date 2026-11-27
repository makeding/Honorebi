import { Buffer } from 'buffer';
import { decodeTS, type ResponseMessage } from 'web-bml/worker';
import { PsiArchiveUnpacker } from './psiArchive';

(globalThis as unknown as { Buffer: typeof Buffer }).Buffer = Buffer;

type StartCommand = {
  type: 'start';
  serviceId: number;
  rawTs: boolean;
};

type ChunkCommand = {
  type: 'chunk';
  data: ArrayBuffer;
};

type StopCommand = {
  type: 'stop';
};

type WorkerCommand = StartCommand | ChunkCommand | StopCommand;

let tsStream: ReturnType<typeof decodeTS> | null = null;
let rawTsMode = false;
let lastPcr = -1;
const unpacker = new PsiArchiveUnpacker();

function emit(message: ResponseMessage): void {
  self.postMessage(message);
}

self.onmessage = (event: MessageEvent<WorkerCommand>) => {
  const msg = event.data;
  if (msg.type === 'start') {
    unpacker.reset();
    lastPcr = -1;
    rawTsMode = msg.rawTs;
    tsStream = decodeTS({
      sendCallback: (decoded) => emit(decoded),
      serviceId: msg.serviceId,
      parsePES: false,
    });
    return;
  }

  if (msg.type === 'stop') {
    tsStream = null;
    unpacker.reset();
    lastPcr = -1;
    return;
  }

  if (msg.type === 'chunk') {
    if (!tsStream) return;
    const bytes = new Uint8Array(msg.data);
    if (rawTsMode) {
      tsStream.parse(Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength));
      return;
    }
    unpacker.push(bytes, (second, psiPackets) => {
      const pcr = Math.floor(second * 90000);
      if (lastPcr !== pcr) {
        emit({
          type: 'pcr',
          pcrBase: pcr,
          pcrExtension: 0,
        });
        lastPcr = pcr;
      }
      tsStream?.parse(
        Buffer.from(psiPackets.buffer as ArrayBuffer, psiPackets.byteOffset, psiPackets.byteLength),
      );
    });
  }
};
