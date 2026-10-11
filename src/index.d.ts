export interface DiagramNode { id: string; label: string; kind?: 'box' | 'text'; }
export interface DiagramEdge { from: string; to: string; label?: string; primary?: boolean; }
export interface Diagram {
  title?: string;
  /** Optional complete accessible description; otherwise generated from nodes and edges. */
  description?: string;
  root?: string;
  nodes: DiagramNode[];
  edges?: DiagramEdge[];
}
export interface LayoutOptions { columns?: number; mode?: 'auto' | 'stacked' | 'references'; }
export interface LayoutResult {
  text: string;
  mode: 'wide' | 'stacked' | 'references';
  columns: number;
  width: number;
  height: number;
}
export interface EnhanceOptions {
  mode?: LayoutOptions['mode'];
  onError?: (error: unknown, element: HTMLPreElement) => void;
}
export interface DiagramController {
  element: HTMLPreElement;
  refresh(): void;
  update(graph: Diagram): void;
  destroy(): void;
}
export function layoutDiagram(graph: Diagram, options?: LayoutOptions): LayoutResult;
export function renderDiagram(graph: Diagram, options?: LayoutOptions): string;
export function describeDiagram(graph: Diagram): string;
export function renderMarkup(graph: Diagram, options?: LayoutOptions & { id?: string }): string;
export function enhance(pre: HTMLPreElement, graph: Diagram, options?: EnhanceOptions): DiagramController;
export function enhanceAll(root?: Document | Element | DocumentFragment, options?: EnhanceOptions): DiagramController[];
