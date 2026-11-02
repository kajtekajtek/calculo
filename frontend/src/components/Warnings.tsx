export default function Warnings({ warnings }: { warnings: string[] }) {
  if (warnings.length === 0) return null;
  return (
    <div role="status" className="warnings">
      {warnings.map((w) => <p key={w}>{w}</p>)}
    </div>
  );
}
