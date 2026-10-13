import { Client, type IMessage } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { api } from "./api";
import { wsUrl } from "./config";
import type { IncidentStateEvent, ProposalMessage } from "./types";

// Live feed client: STOMP over SockJS (the server endpoint registers both),
// with reconnect so the dashboard survives incident-svc restarts.
//
// The feed is authenticated. The browser cannot hold the control-plane key, so
// before every connect attempt it asks its own origin for a short-lived,
// single-use ticket. That request goes through the proxy, which injects the key
// server-side, and the ticket is sent in the STOMP CONNECT frame.

export interface LiveFeedHandlers {
  onIncident: (event: IncidentStateEvent) => void;
  onProposal: (proposal: ProposalMessage) => void;
  onConnected: (connected: boolean) => void;
}

export function connectLiveFeed(handlers: LiveFeedHandlers): () => void {
  const client = new Client({
    webSocketFactory: () => new SockJS(wsUrl()),
    reconnectDelay: 3000,
    beforeConnect: async () => {
      // A fresh ticket per attempt: the previous one was spent on connect.
      const { ticket } = await api.wsTicket();
      client.connectHeaders = { "X-Auth-Ticket": ticket };
    },
    onConnect: () => {
      client.subscribe("/topic/incidents", (msg: IMessage) =>
        handlers.onIncident(JSON.parse(msg.body) as IncidentStateEvent),
      );
      client.subscribe("/topic/proposals", (msg: IMessage) =>
        handlers.onProposal(JSON.parse(msg.body) as ProposalMessage),
      );
      handlers.onConnected(true);
    },
    onWebSocketClose: () => handlers.onConnected(false),
    onStompError: () => handlers.onConnected(false),
    // A rejected CONNECT (expired ticket, or the feed's auth gate refusing a
    // ticketless session) surfaces here; the status badge stays offline until a
    // reconnect with a fresh ticket succeeds.
    onWebSocketError: () => handlers.onConnected(false),
  });
  client.activate();
  return () => {
    void client.deactivate();
  };
}