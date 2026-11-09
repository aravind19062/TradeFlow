import { Link } from 'react-router';
import { Card, EmptyState } from '../components/ui';

export function NotFoundPage() {
  return (
    <Card>
      <EmptyState title="Page not found">
        <Link to="/" className="text-indigo-600 hover:underline">Back to the dashboard</Link>
      </EmptyState>
    </Card>
  );
}
