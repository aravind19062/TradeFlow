import { useState } from 'react';
import { portfolioApi } from '../api/endpoints';
import { useApi } from './useApi';

/**
 * Portfolio summary. Right after registration the account does not exist yet
 * (portfolio-service opens it from the users.registered event) and the API answers
 * 404 ACCOUNT_NOT_FOUND; poll quickly until it appears, then every 30 s (one market tick).
 */
export function usePortfolioSummary() {
  const [opening, setOpening] = useState(false);
  const state = useApi(() => portfolioApi.summary(), [], { topics: ['portfolio'], intervalMs: opening ? 1_500 : 30_000 });
  const notFound = state.error?.code === 'ACCOUNT_NOT_FOUND';
  if (notFound !== opening) setOpening(notFound);
  return { ...state, opening: notFound, error: notFound ? null : state.error, loading: state.loading || notFound };
}
