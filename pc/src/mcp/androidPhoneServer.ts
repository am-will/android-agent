import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { registerPhoneTools } from "./tools.js";

const server = new McpServer({
  name: "android-phone",
  version: "0.1.0"
});

registerPhoneTools(server);

const transport = new StdioServerTransport();
await server.connect(transport);
