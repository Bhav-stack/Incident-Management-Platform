import { Client, type IMessage } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import type { IncidentStateEvent, ProposalMessage } from "./types";

// Live feed client: STOMP over SockJS (the server endpoint registers both),
// with reconnect so the dashboard survives incident-svc restarts.

export interface LiveFeedHandlers {
  onIncident: (event: IncidentStateEvent) => void;
  onProposal: (proposal: ProposalMessage) => void;
  onConnected: (connected: boolean) => void;
}

export function connectLiveFeed(handlers: LiveFeedHandlers): () => void {
  const client = new Client({
    webSocketFactory: () => new SockJS("/ws"),
    reconnectDelay: 3000,
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
  });
  client.activate();
  return () => {
    void client.deactivate();
  };
}