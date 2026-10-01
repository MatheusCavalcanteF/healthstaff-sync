"""Ponto de entrada do microsserviço de extração.

Desenvolvimento:  uvicorn main:app --reload
Produção:         uvicorn main:app --host 0.0.0.0 --port 8000
"""

from api.app import create_app

app = create_app()

if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="127.0.0.1", port=8000)
