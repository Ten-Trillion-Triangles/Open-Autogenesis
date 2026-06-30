import { createGrpcWebTransport } from "@connectrpc/connect-web";
import { MethodKind } from "@bufbuild/protobuf";
import { RpcEnvelope } from "@autogenesis/grpc/rpc_bridge_pb.js";

function createGrpcWebClient(baseUrl) {
  console.info("grpc-web-client: createGrpcWebClient start", baseUrl);
  try {
    const transport = createGrpcWebTransport({ baseUrl });
    console.info("grpc-web-client: transport created", transport ? Object.keys(transport) : []);
    const rpcBridgeInvoke = {
      typeName: "autogenesis.rpc.RpcBridge",
      methods: {
        invoke: {
          name: "Invoke",
          I: RpcEnvelope,
          O: RpcEnvelope,
          kind: MethodKind.Unary,
        },
      },
    };
    console.info("grpc-web-client: method descriptor ready", rpcBridgeInvoke.methods.invoke.kind);
    return {
      invoke: async (envelope, options = {}) => {
        const response = await transport.unary(
          rpcBridgeInvoke,
          rpcBridgeInvoke.methods.invoke,
          options.signal,
          options.timeoutMs,
          options.headers,
          envelope,
          options.contextValues,
        );
        return response.message;
      },
    };
  } catch (err) {
    console.error("grpc-web-client: failed during client creation", err);
    throw err;
  }
}

export default createGrpcWebClient;
export { createGrpcWebClient };
globalThis.__autogenesisCreateGrpcWebClient = createGrpcWebClient;
