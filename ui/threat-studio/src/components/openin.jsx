import { Icon } from './icons';
import { boUrl } from '../lib/backoffice';

export function OpenInOtoroshi({ plural, id, label = 'Open' }) {
  return (
    <a
      className="btn sm"
      href={boUrl(plural, id)}
      target="_blank"
      rel="noreferrer"
      title="Open the full form in the Otoroshi admin console"
    >
      <Icon name="external" />
      {label}
    </a>
  );
}
