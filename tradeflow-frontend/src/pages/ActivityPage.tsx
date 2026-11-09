import { useState } from 'react';
import { History } from 'lucide-react';
import { sessionApi } from '../api/endpoints';
import type { ActivityCategory } from '../api/types';
import { Button, Card, EmptyState, ErrorState, PageHeader, SelectInput, Spinner } from '../components/ui';
import { dateTime } from '../lib/format';
import { deviceName, EVENT_LABELS, isWarningEvent } from '../lib/securityEvents';
import { useApi } from '../lib/useApi';

/** The full, filterable record of what happened on this account. */
export function ActivityPage() {
  const [category, setCategory] = useState<ActivityCategory>('');
  const [page, setPage] = useState(0);
  const activity = useApi(() => sessionApi.activity(category, page), [category, page]);
  const data = activity.data;

  return (
    <>
      <PageHeader icon={<History className="size-5" />} tone="violet" title="Account activity" subtitle="Sign-ins, security changes and profile changes on your account." />
      <div className="mb-4 max-w-xs">
        <SelectInput label="Show" value={category} onChange={(e) => { setCategory(e.target.value as ActivityCategory); setPage(0); }}>
          <option value="">All activity</option>
          <option value="signin">Sign-ins and devices</option>
          <option value="security">Security changes</option>
          <option value="profile">Profile and bank</option>
          <option value="failed">Failed attempts</option>
        </SelectInput>
      </div>
      <Card bodyClassName="p-0">
        {activity.error ? <ErrorState error={activity.error} onRetry={activity.reload} compact />
          : !data ? <Spinner />
            : data.items.length === 0 ? <EmptyState title="Nothing to show">No activity matches this filter.</EmptyState>
              : <ul className="divide-y divide-slate-100 text-sm" data-testid="activity-list">
                {data.items.map((e, i) => (
                  <li key={`${e.occurredAt}-${i}`} className="flex items-start justify-between gap-3 px-5 py-3">
                    <div className="min-w-0">
                      <p className={isWarningEvent(e.type) || e.byAdministrator ? 'font-medium text-rose-700' : 'font-medium text-slate-900'}>
                        {EVENT_LABELS[e.type] ?? e.type}{e.detail && <span className="font-normal text-slate-500"> · {e.detail}</span>}
                      </p>
                      <p className="truncate text-xs text-slate-500">{deviceName(e.userAgent)}{e.clientIp && ` · ${e.clientIp}`}</p>
                    </div>
                    <span className="whitespace-nowrap text-xs text-slate-500">{dateTime(e.occurredAt)}</span>
                  </li>
                ))}
              </ul>}
      </Card>
      {data && data.totalPages > 1 && (
        <nav aria-label="Pages" className="mt-4 flex items-center justify-between text-sm">
          <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</Button>
          <span className="text-slate-500">Page {data.page + 1} of {data.totalPages}</span>
          <Button variant="secondary" size="sm" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Older</Button>
        </nav>
      )}
    </>
  );
}
