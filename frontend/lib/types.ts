// Shapes returned by the Spring Boot API.

export interface ToolCall {
  round: number;
  tool: string;
  arguments: string;       // raw JSON string produced by the model
  resultPreview: string;   // first part of what the tool returned
}

export interface AgentResult {
  answer: string;
  toolCalls: ToolCall[];
}

export interface SourceRow {
  source: string;
  rows: number;
  documents: number;
  embedded: number;
  in_progress: number;
  latest_event_date: string | null;
}

export interface StatusCount {
  status: string;
  rows: number;
}

export interface TariffRateRow {
  ingredients: string;
  hts_code: string;
  general_rate: string | null;
  fetched_at: string | number | null;
  changed_at: string | number | null;
}

export interface IngestionRun {
  started_at: string | number;
  finished_at: string | number | null;
  trigger: string;
  result: string | null;
  error: string | null;
}

export interface PipelineStatus {
  eventsBySource: SourceRow[];
  statusCounts: StatusCount[];
  tariffRates: TariffRateRow[];
  recentRuns: IngestionRun[];
}
