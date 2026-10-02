import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@store-app/ui';

export function ShopHome() {
  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Your shop</h1>
        <p className="text-muted-foreground">
          Orders, products, wallet and appearance, all in one place.
        </p>
      </header>

      <Card>
        <CardHeader>
          <CardTitle>Getting started</CardTitle>
          <CardDescription>
            Phase 1 builds this in order, because each step depends on the one before it.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <ol className="list-decimal space-y-2 pl-5 text-sm text-muted-foreground">
            <li>Complete your shop profile and pick your shop type.</li>
            <li>Submit verification so an admin can approve the shop.</li>
            <li>Add your first product with images and variants.</li>
            <li>Fund your wallet so you can accept orders.</li>
            <li>Publish your storefront and start selling.</li>
          </ol>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>How you get paid</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm text-muted-foreground">
          <p>
            Customers pay you directly through eSewa, cash on delivery, or bank transfer. We
            never hold your money.
          </p>
          <p>
            The platform takes a 5% commission when you accept an order, drawn from your
            pre-funded wallet. A subscription removes that commission entirely.
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
