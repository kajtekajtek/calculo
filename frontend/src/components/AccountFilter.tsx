import { Account } from "../api";

interface Props {
  accounts: Account[];
  selected: string;
  onSelect: (id: string) => void;
}

export default function AccountFilter({ accounts, selected, onSelect }: Props) {
  if (accounts.length === 0) return null;
  const options = [{ id: "all", name: "Wszystkie" }, ...accounts];
  return (
    <div className="filter" role="group" aria-label="Konto">
      {options.map((o) => (
        <button key={o.id} type="button" aria-pressed={o.id === selected} onClick={() => onSelect(o.id)}>
          {o.name}
        </button>
      ))}
    </div>
  );
}
